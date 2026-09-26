package io.github.castab.commerce.runtime.persistence

import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.jdbi.v3.core.Jdbi
import java.util.UUID
import javax.sql.DataSource

/**
 * Flyway discovery, the commerce schema's upgrade path, and the transaction boundary, against
 * a real PostgreSQL. The migration lifecycle itself is covered by [MigrationLifecycleSpec].
 */
class PersistenceSpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var dataSource: HikariDataSource
        lateinit var jdbi: Jdbi

        beforeSpec {
            database = TestDatabase.create()
            dataSource = createDataSource(database.configuration, poolName = "persistence-spec")
            MigrationLifecycle(dataSource, applicationLocations = listOf("classpath:db/testapp")).migrate()
            jdbi = Jdbi.create(dataSource)
        }

        afterSpec {
            dataSource.close()
            database.close()
        }

        fun Jdbi.count(table: String): Int =
            withHandle<Int, Exception> {
                it.createQuery("SELECT count(*) FROM $table").mapTo(Int::class.java).one()
            }

        fun Jdbi.tableExists(table: String): Boolean =
            withHandle<String?, Exception> {
                it
                    .createQuery("SELECT to_regclass(:table)::text")
                    .bind("table", table)
                    .mapTo(String::class.java)
                    .one()
            } != null

        fun Jdbi.appliedVersions(historyTable: String): List<String> =
            withHandle<List<String>, Exception> {
                it
                    .createQuery("SELECT version FROM $historyTable WHERE success AND version IS NOT NULL ORDER BY installed_rank")
                    .mapTo(String::class.java)
                    .list()
            }

        context("migrations") {
            test("every migration succeeds from an empty database; commerce migrations own the commerce schema and history") {
                jdbi.appliedVersions("commerce.flyway_schema_history") shouldContainExactly
                    listOf("20260926120000", "20260926180000")
            }

            test("after every commerce migration the runtime owns no customer table") {
                jdbi.tableExists("commerce.customers") shouldBe false
            }

            test("application migrations run afterwards with their own history in the default schema") {
                jdbi.appliedVersions("public.flyway_schema_history") shouldContainExactly listOf("1")
            }

            test("migrating again is a no-op") {
                MigrationLifecycle(dataSource, applicationLocations = listOf("classpath:db/testapp")).migrate()

                jdbi.count("commerce.flyway_schema_history WHERE version IS NOT NULL") shouldBe 2
            }

            test("the database is reachable for readiness checks") {
                dataSource.isReachable() shouldBe true
            }
        }

        context("upgrading a commerce schema created by commerce-runtime 0.0.4") {
            // Migrates a fresh database only as far as the 0.0.4 schema (which still had
            // commerce.customers), with the same settings RuntimeMigrations uses.
            fun migrateTo004(dataSource: DataSource) {
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .schemas(RuntimeMigrations.SCHEMA)
                    .createSchemas(true)
                    .table(MigrationStream.HISTORY_TABLE)
                    .locations(RuntimeMigrations.LOCATION)
                    .target("20260926120000")
                    .load()
                    .migrate()
            }

            fun withUpgradeDatabase(block: (DataSource, Jdbi) -> Unit) {
                TestDatabase.create().use { upgrade ->
                    createDataSource(upgrade.configuration, poolName = "persistence-upgrade-spec").use { source ->
                        migrateTo004(source)
                        block(source, Jdbi.create(source))
                    }
                }
            }

            test("the forward migration drops the obsolete customer table") {
                withUpgradeDatabase { source, upgraded ->
                    upgraded.tableExists("commerce.customers") shouldBe true

                    RuntimeMigrations(source).migrate()

                    upgraded.tableExists("commerce.customers") shouldBe false
                    upgraded.appliedVersions("commerce.flyway_schema_history") shouldContainExactly
                        listOf("20260926120000", "20260926180000")
                }
            }

            test("the forward migration drops the table unconditionally, even when it still holds rows") {
                withUpgradeDatabase { source, upgraded ->
                    upgraded.useHandle<Exception> {
                        it.execute(
                            "INSERT INTO commerce.customers (id, name, email) VALUES (?, 'Ada Lovelace', 'ada@example.com')",
                            UUID.randomUUID(),
                        )
                    }

                    RuntimeMigrations(source).migrate()

                    upgraded.tableExists("commerce.customers") shouldBe false
                    upgraded.appliedVersions("commerce.flyway_schema_history") shouldContainExactly
                        listOf("20260926120000", "20260926180000")
                }
            }
        }

        context("transactions") {
            val transactor = Transactor(jdbi)

            // Stand-ins for application repositories that receive the same Transaction. Every write
            // here is application-owned: the runtime has no commerce-owned repository yet, so these
            // tests prove the shared transaction boundary, not atomicity across application and
            // commerce persistence. See the future test requirement in AGENTS.md.
            fun insertRecord(
                transaction: Transaction,
                value: String,
            ): UUID =
                UUID.randomUUID().also { id ->
                    transaction.handle
                        .createUpdate("INSERT INTO public.test_application_records (id, value) VALUES (:id, :value)")
                        .bind("id", id)
                        .bind("value", value)
                        .execute()
                }

            fun findRecord(
                transaction: Transaction,
                id: UUID,
            ): String? =
                transaction.handle
                    .createQuery("SELECT value FROM public.test_application_records WHERE id = :id")
                    .bind("id", id)
                    .mapTo(String::class.java)
                    .findOne()
                    .orElse(null)

            test("every write made through one transaction commits together") {
                val before = jdbi.count("public.test_application_records")

                val (first, second) =
                    transactor.inTransaction { transaction ->
                        insertRecord(transaction, "first application row") to insertRecord(transaction, "second application row")
                    }

                transactor.inTransaction { findRecord(it, first) } shouldBe "first application row"
                transactor.inTransaction { findRecord(it, second) } shouldBe "second application row"
                jdbi.count("public.test_application_records") shouldBe before + 2
            }

            test("a failure rolls back every write in the transaction and rethrows the original exception") {
                val before = jdbi.count("public.test_application_records")
                var written: UUID? = null

                shouldThrow<IllegalStateException> {
                    transactor.inTransaction { transaction ->
                        written = insertRecord(transaction, "first application row")
                        insertRecord(transaction, "second application row")
                        throw IllegalStateException("application policy rejected the operation")
                    }
                }.message shouldBe "application policy rejected the operation"

                jdbi.count("public.test_application_records") shouldBe before
                transactor.inTransaction { findRecord(it, written!!) }.shouldBeNull()
            }

            test("the block's result is returned") {
                transactor.inTransaction { 42 } shouldBe 42
            }
        }
    })
