package io.github.castab.commerce.runtime

import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration.Migrations.OnStartup
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.createDataSource
import io.github.castab.commerce.runtime.testing.TEST_APPLICATION_SCHEMA
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.appliedVersions
import io.github.castab.commerce.runtime.testing.execute
import io.github.castab.commerce.runtime.testing.relationExists
import io.github.castab.commerce.runtime.testing.testApplicationMigrations
import io.github.castab.commerce.runtime.testing.withTestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeInstanceOf
import org.flywaydb.core.api.FlywayException
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.util.Base64

/**
 * Startup gating: `commerceRuntime(...)` runs the migration phase before it composes
 * anything, so a failed or incompatible database yields an exception, never a
 * [CommerceRuntime] whose server could be started.
 */
class CommerceRuntimeStartupSpec :
    FunSpec({
        val application = ApplicationContributions(migrations = testApplicationMigrations("classpath:db/testapp"))

        fun TestDatabase.runtimeConfiguration(onStartup: OnStartup) =
            CommerceRuntimeConfiguration(
                server = CommerceRuntimeConfiguration.Server(port = 0),
                database = configuration,
                migrations = CommerceRuntimeConfiguration.Migrations(onStartup = onStartup),
            )

        context("a configuration constructed in code is validated before any resource is opened") {
            // Nothing listens on port 1: had composition opened the pool first, the failure
            // would be a connection error rather than the named configuration error.
            val unreachable = CommerceRuntimeConfiguration.Database("jdbc:postgresql://127.0.0.1:1/none", "commerce", "commerce")
            val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

            fun rejected(configuration: CommerceRuntimeConfiguration): String? =
                shouldThrow<IllegalArgumentException> { commerceRuntime(configuration, ApplicationContributions()) }.message

            fun withTokens(tokens: CommerceRuntimeConfiguration.ServiceTokens) =
                CommerceRuntimeConfiguration(database = unreachable, serviceTokens = tokens)

            test("invalid general settings are rejected exactly as load() rejects them") {
                rejected(CommerceRuntimeConfiguration(database = unreachable.copy(password = ""))) shouldBe "DATABASE_PASSWORD is required"
                rejected(CommerceRuntimeConfiguration(database = unreachable, sessions = CommerceRuntimeConfiguration.Sessions(0))) shouldBe
                    "SESSIONS_LIFETIME_MINUTES must be between 1 and 525600"
            }

            test("every service token rule applies to a directly constructed configuration") {
                rejected(
                    withTokens(
                        CommerceRuntimeConfiguration.ServiceTokens(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }), "orders"),
                    ),
                ) shouldBe
                    "SERVICE_TOKENS_SIGNING_KEY must be random, not a repeated byte"
                rejected(
                    withTokens(
                        CommerceRuntimeConfiguration.ServiceTokens(
                            Base64.getEncoder().encodeToString(
                                ByteArray(31) {
                                    it.toByte()
                                },
                            ),
                            "orders",
                        ),
                    ),
                ) shouldBe
                    "SERVICE_TOKENS_SIGNING_KEY must encode at least 32 bytes"
                rejected(withTokens(CommerceRuntimeConfiguration.ServiceTokens("not base64!", "orders"))) shouldBe
                    "SERVICE_TOKENS_SIGNING_KEY must be base64"
                rejected(withTokens(CommerceRuntimeConfiguration.ServiceTokens(key, " production "))) shouldBe
                    "SERVICE_TOKENS_ISSUER must not have surrounding whitespace"
                rejected(withTokens(CommerceRuntimeConfiguration.ServiceTokens(key, ""))) shouldBe
                    "SERVICE_TOKENS_ISSUER is required when service tokens are configured"
                rejected(withTokens(CommerceRuntimeConfiguration.ServiceTokens(key, " "))) shouldBe
                    "SERVICE_TOKENS_ISSUER is required when service tokens are configured"
                listOf(0L, 61L).forEach { minutes ->
                    rejected(withTokens(CommerceRuntimeConfiguration.ServiceTokens(key, "orders", minutes))) shouldBe
                        "SERVICE_TOKENS_LIFETIME_MINUTES must be between 1 and 60"
                }
            }

            test("a valid configuration passes validation and only then reaches the database") {
                shouldThrow<Exception> {
                    commerceRuntime(withTokens(CommerceRuntimeConfiguration.ServiceTokens(key, "orders")), ApplicationContributions())
                }.shouldNotBeInstanceOf<IllegalArgumentException>()
            }
        }

        test("a failed runtime migration stream prevents the application migrations and composition") {
            withTestDatabase { database, dataSource ->
                MigrationLifecycle(dataSource).migrate()
                dataSource.execute("UPDATE commerce.flyway_schema_history SET checksum = checksum + 1 WHERE version IS NOT NULL")

                shouldThrow<FlywayException> { commerceRuntime(database.runtimeConfiguration(OnStartup.MIGRATE), application) }

                dataSource.relationExists("$TEST_APPLICATION_SCHEMA.flyway_schema_history") shouldBe false
                dataSource.relationExists("public.flyway_schema_history") shouldBe false
            }
        }

        test("a failed application migration prevents composition") {
            withTestDatabase { database, dataSource ->
                val broken = ApplicationContributions(migrations = testApplicationMigrations("classpath:db/testapp-broken"))

                shouldThrow<FlywayException> { commerceRuntime(database.runtimeConfiguration(OnStartup.MIGRATE), broken) }

                dataSource.appliedVersions("commerce").shouldNotBeEmpty()
                dataSource.appliedVersions(TEST_APPLICATION_SCHEMA).shouldBeEmpty()
            }
        }

        test("validating on startup refuses a database the migrations have not reached, and changes nothing") {
            withTestDatabase { database, dataSource ->
                shouldThrow<FlywayException> { commerceRuntime(database.runtimeConfiguration(OnStartup.VALIDATE), application) }

                dataSource.relationExists("commerce.flyway_schema_history") shouldBe false
            }
        }

        test("a separate migration step, then instances that only validate, start and serve") {
            withTestDatabase { database, _ ->
                // The migration step: no runtime is composed and no server is started.
                createDataSource(database.configuration, poolName = "migration-step").use { dataSource ->
                    MigrationLifecycle(dataSource, application.migrations).migrate()
                }

                commerceRuntime(database.runtimeConfiguration(OnStartup.VALIDATE), application).use { first ->
                    commerceRuntime(database.runtimeConfiguration(OnStartup.VALIDATE), application).use { second ->
                        first.http(Request(Method.GET, "/ready")).status shouldBe Status.OK
                        second.http(Request(Method.GET, "/ready")).status shouldBe Status.OK
                    }
                }
            }
        }

        test("instances started one after another against a migrated database each compose") {
            withTestDatabase { database, dataSource ->
                repeat(2) {
                    commerceRuntime(database.runtimeConfiguration(OnStartup.MIGRATE), application).start().use { runtime ->
                        runtime.http(Request(Method.GET, "/ready")).status shouldBe Status.OK
                    }
                }

                dataSource.appliedVersions(TEST_APPLICATION_SCHEMA) shouldBe listOf("1")
                dataSource.relationExists("public.flyway_schema_history") shouldBe false
            }
        }
    })
