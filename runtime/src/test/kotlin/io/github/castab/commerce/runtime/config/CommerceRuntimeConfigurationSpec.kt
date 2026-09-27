package io.github.castab.commerce.runtime.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration

class CommerceRuntimeConfigurationSpec :
    FunSpec({
        val database =
            mapOf(
                "DATABASE_JDBC_URL" to "jdbc:postgresql://localhost:5432/commerce",
                "DATABASE_USERNAME" to "commerce",
                "DATABASE_PASSWORD" to "not-a-real-secret",
            )

        test("the application's application.conf is read, the model supplies what it omits, and the environment supplies the database") {
            val configuration = CommerceRuntimeConfiguration.load(environment = database)

            // From the application's file (src/test/resources/application.conf).
            configuration.server.port shouldBe 8081
            configuration.database.maximumPoolSize shouldBe 6
            configuration.sessions.lifetimeMinutes shouldBe 480
            // From the environment.
            configuration.database.jdbcUrl shouldBe "jdbc:postgresql://localhost:5432/commerce"
            configuration.database.username shouldBe "commerce"
            configuration.database.password shouldBe "not-a-real-secret"
            // Model defaults for settings the file omits.
            configuration.database.minimumIdle shouldBe 1
            configuration.database.connectionTimeoutMs shouldBe 500
            configuration.database.validationTimeoutMs shouldBe 1000
            configuration.migrations.onStartup shouldBe CommerceRuntimeConfiguration.Migrations.OnStartup.VALIDATE
        }

        test("commerce-runtime ships no configuration: a missing application resource is rejected") {
            shouldThrow<IllegalArgumentException> {
                CommerceRuntimeConfiguration.load(environment = database, resource = "/no-such-application.conf")
            }.message shouldBe
                "Configuration resource /no-such-application.conf was not found on the classpath; " +
                "the concrete application supplies it"
        }

        test("the application's file must declare the database block") {
            shouldThrow<Exception> {
                CommerceRuntimeConfiguration.load(environment = database, resource = "/without-database.conf")
            }.message shouldContain "database"
        }

        test("every documented environment variable overrides its setting") {
            val configuration =
                CommerceRuntimeConfiguration.load(
                    environment =
                        database +
                            mapOf(
                                "PORT" to "9090",
                                "DATABASE_MAXIMUM_POOL_SIZE" to "10",
                                "DATABASE_MINIMUM_IDLE" to "2",
                                "DATABASE_CONNECTION_TIMEOUT_MS" to "750",
                                "DATABASE_VALIDATION_TIMEOUT_MS" to "1500",
                                "MIGRATIONS_ON_STARTUP" to "migrate",
                                "SESSIONS_LIFETIME_MINUTES" to "30",
                            ),
                )

            configuration.server.port shouldBe 9090
            configuration.database.maximumPoolSize shouldBe 10
            configuration.database.minimumIdle shouldBe 2
            configuration.database.connectionTimeoutMs shouldBe 750
            configuration.database.validationTimeoutMs shouldBe 1500
            configuration.migrations.onStartup shouldBe CommerceRuntimeConfiguration.Migrations.OnStartup.MIGRATE
            configuration.sessions.lifetimeMinutes shouldBe 30
            configuration.sessions.lifetime shouldBe Duration.ofMinutes(30)
        }

        test("sessions expire after 12 hours unless the application chooses otherwise") {
            CommerceRuntimeConfiguration.Sessions().lifetime shouldBe Duration.ofHours(12)
        }

        test("the session lifetime must be between one minute and one year") {
            shouldThrow<IllegalArgumentException> {
                CommerceRuntimeConfiguration.load(environment = database + ("SESSIONS_LIFETIME_MINUTES" to "0"))
            }.message shouldBe "SESSIONS_LIFETIME_MINUTES must be between 1 and 525600"
            shouldThrow<IllegalArgumentException> {
                CommerceRuntimeConfiguration.load(environment = database + ("SESSIONS_LIFETIME_MINUTES" to "525601"))
            }.message shouldBe "SESSIONS_LIFETIME_MINUTES must be between 1 and 525600"
            shouldThrow<IllegalArgumentException> {
                CommerceRuntimeConfiguration.load(environment = database + ("SESSIONS_LIFETIME_MINUTES" to "12h"))
            }.message shouldBe "SESSIONS_LIFETIME_MINUTES must be an integer"
            CommerceRuntimeConfiguration
                .load(environment = database + ("SESSIONS_LIFETIME_MINUTES" to "525600"))
                .sessions.lifetime shouldBe Duration.ofDays(365)
        }

        test("unrelated environment variables are ignored") {
            val configuration = CommerceRuntimeConfiguration.load(environment = database + ("SERVER_PORT" to "1"))

            configuration.server.port shouldBe 8081
        }

        test("a missing database is rejected, naming the variable") {
            shouldThrow<IllegalArgumentException> {
                CommerceRuntimeConfiguration.load(environment = emptyMap())
            }.message shouldBe "DATABASE_JDBC_URL is required"
        }

        test("malformed values are rejected, naming the variable") {
            shouldThrow<IllegalArgumentException> {
                CommerceRuntimeConfiguration.load(environment = database + ("PORT" to "eighty"))
            }.message shouldBe "PORT must be an integer"
            shouldThrow<IllegalArgumentException> {
                CommerceRuntimeConfiguration.load(environment = database + ("MIGRATIONS_ON_STARTUP" to "yes"))
            }.message shouldBe "MIGRATIONS_ON_STARTUP must be migrate or validate"
            shouldThrow<IllegalArgumentException> {
                CommerceRuntimeConfiguration.load(environment = database + ("DATABASE_JDBC_URL" to "jdbc:h2:mem:test"))
            }.message shouldBe "DATABASE_JDBC_URL must be a PostgreSQL JDBC URL"
            shouldThrow<IllegalArgumentException> {
                CommerceRuntimeConfiguration.load(environment = database + ("DATABASE_MINIMUM_IDLE" to "7"))
            }.message shouldBe "DATABASE_MINIMUM_IDLE must be between zero and DATABASE_MAXIMUM_POOL_SIZE"
        }

        test("the database password never appears in the configuration's text") {
            val configuration = CommerceRuntimeConfiguration.load(environment = database)

            configuration.toString() shouldNotContain "not-a-real-secret"
            configuration.database.toString() shouldContain "password=****"
        }
    })
