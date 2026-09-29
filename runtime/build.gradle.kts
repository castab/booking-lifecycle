import java.util.UUID

// commerce-runtime: the opinionated, reusable runtime from which concrete commerce
// applications are assembled. It is a library, not an application: it applies no
// `application` plugin and has no main(). Concrete applications depend on it, supply
// their ApplicationContributions, and own their executable and process lifecycle.
//
// It depends on :domain (never the reverse) and carries the runtime stack:
// http4k on Jetty, kotlinx.serialization, PostgreSQL through HikariCP and JDBI, Flyway,
// Hoplite/HOCON configuration, and Kotlin Logging on the SLF4J API. Shared toolchain,
// test, lint, and publishing conventions come from the root build.gradle.kts.
plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

base {
    archivesName = "commerce-runtime"
}

dependencies {
    // Within this build the domain is a project dependency; in the published POM it
    // becomes io.github.castab:commerce-domain at the same version.
    api(project(":domain"))

    // Types from these libraries appear in the public runtime API (routes, handlers,
    // JSON lenses, transactions, the connection pool), so consumers compile against them.
    api(libs.http4k.core)
    api(libs.http4k.api.openapi)
    api(libs.http4k.format.kotlinx.serialization)
    api(libs.kotlinx.serialization.json)
    api(libs.jdbi.core)
    api(libs.hikaricp)

    implementation(libs.http4k.server.jetty)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.hoplite.core)
    implementation(libs.hoplite.hocon)
    // Hoplite decodes configuration data classes through Kotlin reflection.
    implementation(kotlin("reflect"))
    // The runtime logs through Kotlin Logging on the SLF4J API only. It never selects an
    // SLF4J provider (Logback or any other): the concrete application owns the logging
    // backend and its configuration.
    implementation(libs.kotlin.logging.jvm)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.http4k.format.jackson)
    // The runtime's own tests choose Logback as their SLF4J provider, configured by
    // src/test/resources/logback-test.xml. It never reaches the published dependencies.
    testRuntimeOnly(libs.logback.classic)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "commerce-runtime"
            from(components["java"])

            pom {
                name = "commerce-runtime"
                description = "Opinionated, reusable runtime for assembling commerce applications on " +
                    "commerce-domain: http4k on Jetty, kotlinx.serialization, PostgreSQL through " +
                    "HikariCP, JDBI, and Flyway, Hoplite/HOCON configuration, explicit " +
                    "transactions, immutable financial snapshots, payment allocations, and refunds, " +
                    "derived reconciliation, a consistent HTTP error contract, authenticated principal sessions and " +
                    "permission enforcement, and explicit composition."
            }
        }
    }
}

// ---------------------------------------------------------------------------
// PostgreSQL for tests, through the plain Docker CLI.
//
// Database specs run against a real PostgreSQL with the real Flyway migrations; there is
// no H2 and no separate test schema. The first test JVM that needs the database starts
// a throwaway container through `docker run`; Gradle removes it when the build ends,
// including when tests fail. Each spec creates, migrates, and drops its own database.
//
// When TEST_DATABASE_JDBC_URL is set (with TEST_DATABASE_USERNAME and
// TEST_DATABASE_PASSWORD), no container is started and that server is used instead. The
// user must be allowed to CREATE DATABASE.
//
// Testcontainers is deliberately not used.
// ---------------------------------------------------------------------------
abstract class PostgresTestDatabase :
    BuildService<PostgresTestDatabase.Parameters>,
    AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val image: Property<String>
        val externalJdbcUrl: Property<String>
        val externalUsername: Property<String>
        val externalPassword: Property<String>
    }

    class Connection(
        val jdbcUrl: String,
        val username: String,
        val password: String,
    )

    private var containerName: String? = null

    val connection: Connection by lazy {
        if (parameters.externalJdbcUrl.isPresent) {
            Connection(
                parameters.externalJdbcUrl.get(),
                parameters.externalUsername.getOrElse("postgres"),
                parameters.externalPassword.getOrElse(""),
            )
        } else {
            startContainer()
        }
    }

    private fun startContainer(): Connection {
        val name = "commerce-test-postgres-${UUID.randomUUID().toString().take(8)}"
        val user = "commerce"
        val password = "commerce"
        docker(
            "run",
            "--detach",
            "--rm",
            "--name",
            name,
            "--label",
            "io.github.castab.commerce.test-database=true",
            "--env",
            "POSTGRES_USER=$user",
            "--env",
            "POSTGRES_PASSWORD=$password",
            "--env",
            "POSTGRES_DB=postgres",
            "--publish",
            "127.0.0.1::5432",
            parameters.image.get(),
        )
        containerName = name
        val port =
            docker("port", name, "5432/tcp")
                .lineSequence()
                .first()
                .substringAfterLast(':')
                .trim()
        // TCP readiness: the image's initdb phase serves only the Unix socket, so a
        // successful TCP check means the real server is accepting connections.
        val deadline = System.nanoTime() + 60_000_000_000L
        while (dockerExit("exec", name, "pg_isready", "-h", "127.0.0.1", "-U", user, "-d", "postgres") != 0) {
            if (System.nanoTime() > deadline) throw GradleException("PostgreSQL test container $name did not become ready")
            Thread.sleep(250)
        }
        return Connection("jdbc:postgresql://127.0.0.1:$port/postgres", user, password)
    }

    override fun close() {
        containerName?.let { dockerExit("rm", "--force", it) }
    }

    private fun docker(vararg arguments: String): String {
        val process = ProcessBuilder("docker", *arguments).redirectErrorStream(true).start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        if (process.waitFor() != 0) {
            throw GradleException("`docker ${arguments.joinToString(" ")}` failed. Is Docker running?\n$output")
        }
        return output
    }

    private fun dockerExit(vararg arguments: String): Int {
        val process = ProcessBuilder("docker", *arguments).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        return process.waitFor()
    }
}

/** Hands the database connection to the test JVM; resolved only when the tests really run. */
class TestDatabaseArguments(
    @get:Internal val database: Provider<PostgresTestDatabase>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val connection = database.get().connection
        return listOf(
            "-Dcommerce.test.database.jdbcUrl=${connection.jdbcUrl}",
            "-Dcommerce.test.database.username=${connection.username}",
            "-Dcommerce.test.database.password=${connection.password}",
        )
    }
}

val postgresTestDatabase =
    gradle.sharedServices.registerIfAbsent("commercePostgresTestDatabase", PostgresTestDatabase::class) {
        parameters.image = "postgres:18-alpine"
        parameters.externalJdbcUrl = providers.environmentVariable("TEST_DATABASE_JDBC_URL")
        parameters.externalUsername = providers.environmentVariable("TEST_DATABASE_USERNAME")
        parameters.externalPassword = providers.environmentVariable("TEST_DATABASE_PASSWORD")
    }

tasks.test {
    usesService(postgresTestDatabase)
    jvmArgumentProviders.add(TestDatabaseArguments(postgresTestDatabase))
}
