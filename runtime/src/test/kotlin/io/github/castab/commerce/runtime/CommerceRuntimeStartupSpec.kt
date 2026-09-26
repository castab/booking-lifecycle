package io.github.castab.commerce.runtime

import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration.Migrations.OnStartup
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.createDataSource
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.appliedVersions
import io.github.castab.commerce.runtime.testing.execute
import io.github.castab.commerce.runtime.testing.relationExists
import io.github.castab.commerce.runtime.testing.withTestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.flywaydb.core.api.FlywayException
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status

/**
 * Startup gating: `commerceRuntime(...)` runs the migration phase before it composes
 * anything, so a failed or incompatible database yields an exception, never a
 * [CommerceRuntime] whose server could be started.
 */
class CommerceRuntimeStartupSpec :
    FunSpec({
        val application = ApplicationContributions(migrationLocations = listOf("classpath:db/testapp"))

        fun TestDatabase.runtimeConfiguration(onStartup: OnStartup) =
            CommerceRuntimeConfiguration(
                server = CommerceRuntimeConfiguration.Server(port = 0),
                database = configuration,
                migrations = CommerceRuntimeConfiguration.Migrations(onStartup = onStartup),
            )

        test("a failed runtime migration stream prevents the application migrations and composition") {
            withTestDatabase { database, dataSource ->
                MigrationLifecycle(dataSource, applicationLocations = emptyList()).migrate()
                dataSource.execute("UPDATE commerce.flyway_schema_history SET checksum = checksum + 1 WHERE version IS NOT NULL")

                shouldThrow<FlywayException> { commerceRuntime(database.runtimeConfiguration(OnStartup.MIGRATE), application) }

                dataSource.relationExists("public.flyway_schema_history") shouldBe false
            }
        }

        test("a failed application migration prevents composition") {
            withTestDatabase { database, dataSource ->
                val broken = ApplicationContributions(migrationLocations = listOf("classpath:db/testapp-broken"))

                shouldThrow<FlywayException> { commerceRuntime(database.runtimeConfiguration(OnStartup.MIGRATE), broken) }

                dataSource.appliedVersions("commerce") shouldContain "1"
                dataSource.appliedVersions("public").shouldBeEmpty()
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
                    MigrationLifecycle(dataSource, application.migrationLocations).migrate()
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

                dataSource.appliedVersions("public") shouldBe listOf("1")
            }
        }
    })
