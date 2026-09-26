package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.runtime.persistence.MigrationStream.Companion.HISTORY_TABLE
import io.github.castab.commerce.runtime.testing.appliedVersions
import io.github.castab.commerce.runtime.testing.execute
import io.github.castab.commerce.runtime.testing.history
import io.github.castab.commerce.runtime.testing.relationExists
import io.github.castab.commerce.runtime.testing.schemaExists
import io.github.castab.commerce.runtime.testing.withTestDatabase
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.api.FlywayException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The migration phase against a real PostgreSQL: the runtime stream is discovered
 * internally, the application stream comes from contributed locations, the runtime stream
 * always runs first, and the two keep independent histories and version spaces. Every test
 * uses a fresh database.
 */
class MigrationLifecycleSpec :
    FunSpec({
        // The first runtime migration. The test application's own migration is also V1.
        val runtimeVersion = "1"
        val testApplication = listOf("classpath:db/testapp")

        test("runtime migrations are discovered internally and own the commerce schema and its history") {
            withTestDatabase { _, dataSource ->
                MigrationLifecycle(dataSource, applicationLocations = emptyList()).migrate()

                dataSource.appliedVersions(RuntimeMigrations.SCHEMA) shouldContain runtimeVersion
                dataSource.relationExists("commerce.customers") shouldBe true
                // No application stream: no application history is created.
                dataSource.relationExists("public.$HISTORY_TABLE") shouldBe false
            }
        }

        test("an application migration depends on a runtime-owned table, so it fails on its own") {
            withTestDatabase { _, dataSource ->
                shouldThrow<FlywayException> {
                    ApplicationMigrations(dataSource, testApplication).migrate()
                }.message shouldContain "schema \"commerce\" does not exist"

                dataSource.relationExists("public.test_application_customer_notes") shouldBe false
            }
        }

        test("the same application migration succeeds because the runtime stream runs first") {
            withTestDatabase { _, dataSource ->
                MigrationLifecycle(dataSource, testApplication).migrate()

                dataSource.appliedVersions(RuntimeMigrations.SCHEMA) shouldContain runtimeVersion
                dataSource.appliedVersions(ApplicationMigrations.SCHEMA) shouldContainExactly listOf("1")
                dataSource.relationExists("public.test_application_customer_notes") shouldBe true
            }
        }

        test("the two streams have independent version spaces, so equal versions do not collide") {
            withTestDatabase { _, dataSource ->
                MigrationLifecycle(dataSource, testApplication).migrate()

                // Runtime V1__commerce_customers.sql and application V1__test_application_customer_notes.sql.
                dataSource.appliedVersions(RuntimeMigrations.SCHEMA).first() shouldBe "1"
                dataSource.appliedVersions(ApplicationMigrations.SCHEMA) shouldContainExactly listOf("1")
                dataSource.relationExists("commerce.customers") shouldBe true
                dataSource.relationExists("public.test_application_customer_notes") shouldBe true
            }
        }

        test("migrating an up-to-date database again applies nothing and leaves both histories unchanged") {
            withTestDatabase { _, dataSource ->
                val lifecycle = MigrationLifecycle(dataSource, testApplication)
                lifecycle.migrate()
                val runtimeHistory = dataSource.history(RuntimeMigrations.SCHEMA)
                val applicationHistory = dataSource.history(ApplicationMigrations.SCHEMA)

                lifecycle.migrate()
                MigrationLifecycle(dataSource, testApplication).migrate()

                dataSource.history(RuntimeMigrations.SCHEMA) shouldBe runtimeHistory
                dataSource.history(ApplicationMigrations.SCHEMA) shouldBe applicationHistory
            }
        }

        test("instances migrating a fresh database at the same time apply each migration exactly once") {
            withTestDatabase { database, _ ->
                val instances = 4
                val barrier = CyclicBarrier(instances)
                val executor = Executors.newFixedThreadPool(instances)
                try {
                    // Each instance has its own pool, as separate processes would.
                    val runs =
                        (1..instances).map { instance ->
                            executor.submit {
                                createDataSource(database.configuration, poolName = "instance-$instance").use { dataSource ->
                                    barrier.await()
                                    MigrationLifecycle(dataSource, testApplication).migrate()
                                }
                            }
                        }
                    runs.forEach { shouldNotThrowAny { it.get(2, TimeUnit.MINUTES) } }
                } finally {
                    executor.shutdownNow()
                }

                createDataSource(database.configuration, poolName = "verification").use { dataSource ->
                    val runtimeVersions = dataSource.appliedVersions(RuntimeMigrations.SCHEMA)
                    runtimeVersions shouldContain runtimeVersion
                    runtimeVersions shouldBe runtimeVersions.distinct()
                    dataSource.appliedVersions(ApplicationMigrations.SCHEMA) shouldContainExactly listOf("1")
                }
            }
        }

        test("a runtime stream that fails validation stops the lifecycle before the application stream") {
            withTestDatabase { _, dataSource ->
                RuntimeMigrations(dataSource).migrate()
                // As if a released runtime migration had been edited after it was applied.
                dataSource.execute("UPDATE commerce.$HISTORY_TABLE SET checksum = checksum + 1 WHERE version = '$runtimeVersion'")

                shouldThrow<FlywayException> {
                    MigrationLifecycle(dataSource, testApplication).migrate()
                }.message shouldContain "checksum mismatch"

                dataSource.relationExists("public.$HISTORY_TABLE") shouldBe false
                dataSource.relationExists("public.test_application_customer_notes") shouldBe false
            }
        }

        test("a failed application migration is not recorded, and the runtime migrations stay applied") {
            withTestDatabase { _, dataSource ->
                shouldThrow<FlywayException> {
                    MigrationLifecycle(dataSource, listOf("classpath:db/testapp-broken")).migrate()
                }.message shouldContain "V1__test_application_broken.sql"

                dataSource.appliedVersions(RuntimeMigrations.SCHEMA) shouldContain runtimeVersion
                dataSource.appliedVersions(ApplicationMigrations.SCHEMA).shouldBeEmpty()
                dataSource.relationExists("public.test_application_broken") shouldBe false
            }
        }

        test("validation applies nothing and fails until both streams are fully applied") {
            withTestDatabase { _, dataSource ->
                val lifecycle = MigrationLifecycle(dataSource, testApplication)

                shouldThrow<FlywayException> { lifecycle.validate() }
                dataSource.schemaExists(RuntimeMigrations.SCHEMA) shouldBe false

                RuntimeMigrations(dataSource).migrate()
                shouldThrow<FlywayException> { lifecycle.validate() }
                dataSource.relationExists("public.$HISTORY_TABLE") shouldBe false

                lifecycle.migrate()
                shouldNotThrowAny { lifecycle.validate() }
            }
        }

        test("validation accepts migrations applied by a later release") {
            withTestDatabase { _, dataSource ->
                MigrationLifecycle(dataSource, testApplication).migrate()
                // As if a later application release had applied V2 already.
                dataSource.execute(
                    "INSERT INTO public.$HISTORY_TABLE " +
                        "(installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) " +
                        "VALUES (100, '2', 'later release', 'SQL', 'V2__later_release.sql', 0, current_user, 0, true)",
                )

                shouldNotThrowAny { MigrationLifecycle(dataSource, testApplication).validate() }
            }
        }

        test("application locations that would also discover the runtime migrations are rejected") {
            withTestDatabase { _, dataSource ->
                listOf(
                    "classpath:db/commerce",
                    "db/commerce",
                    "classpath:/db/commerce/",
                    "classpath:db",
                    "classpath:db/commerce/nested",
                    "classpath:",
                ).forEach { location ->
                    shouldThrow<IllegalArgumentException> {
                        MigrationLifecycle(dataSource, listOf(location))
                    }.message shouldContain "overlaps commerce-runtime's own migrations"
                }

                listOf("classpath:db/migration", "db/commerce-application", "filesystem:db")
                    .forEach { location -> shouldNotThrowAny { MigrationLifecycle(dataSource, listOf(location)) } }
                dataSource.appliedVersions(RuntimeMigrations.SCHEMA).shouldBeEmpty()
            }
        }
    })
