package io.github.castab.commerce.runtime.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.runtime.testing.TEST_APPLICATION_SCHEMA
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.testApplicationMigrations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.transaction.TransactionException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Flyway discovery and the transaction boundary, against a real PostgreSQL. The migration
 * lifecycle itself is covered by [MigrationLifecycleSpec].
 */
class PersistenceSpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var dataSource: HikariDataSource
        lateinit var jdbi: Jdbi

        beforeSpec {
            database = TestDatabase.create()
            dataSource = createDataSource(database.configuration, poolName = "persistence-spec")
            MigrationLifecycle(dataSource, testApplicationMigrations()).migrate()
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

        fun Jdbi.appliedVersions(historyTable: String): List<String> =
            withHandle<List<String>, Exception> {
                it
                    .createQuery("SELECT version FROM $historyTable WHERE success AND version IS NOT NULL ORDER BY installed_rank")
                    .mapTo(String::class.java)
                    .list()
            }

        context("migrations") {
            test("every migration succeeds from an empty database; runtime migrations own the commerce schema and history") {
                jdbi.appliedVersions("commerce.flyway_schema_history") shouldContainExactly
                    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11")
            }

            test("the runtime owns offerings, sessions, authorization, service credential, financial ledger, and refund tables") {
                jdbi.count("pg_tables WHERE schemaname = 'commerce' AND tablename <> 'flyway_schema_history'") shouldBe 14
            }

            test("application migrations run afterwards with their own history in the application's schema") {
                jdbi.appliedVersions("$TEST_APPLICATION_SCHEMA.flyway_schema_history") shouldContainExactly listOf("1")
                jdbi.count("pg_tables WHERE tablename = 'flyway_schema_history' AND schemaname = 'public'") shouldBe 0
            }

            test("migrating again is a no-op") {
                MigrationLifecycle(dataSource, testApplicationMigrations()).migrate()

                jdbi.count("commerce.flyway_schema_history WHERE version IS NOT NULL") shouldBe 11
            }

            test("the database is reachable for readiness checks") {
                dataSource.isReachable() shouldBe true
            }
        }

        context("transactions") {
            val transactor = Transactor(jdbi)

            // Stand-ins for application repositories that receive the same Transaction.
            // Cross-schema atomicity with the offerings repository is covered separately.
            fun insertRecord(
                transaction: Transaction,
                value: String,
            ): UUID =
                UUID.randomUUID().also { id ->
                    transaction.handle
                        .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, :value)")
                        .bind("id", id)
                        .bind("value", value)
                        .execute()
                }

            fun findRecord(
                transaction: Transaction,
                id: UUID,
            ): String? =
                transaction.handle
                    .createQuery("SELECT value FROM testapp.test_application_records WHERE id = :id")
                    .bind("id", id)
                    .mapTo(String::class.java)
                    .findOne()
                    .orElse(null)

            test("every write made through one transaction commits together") {
                val before = jdbi.count("testapp.test_application_records")

                val (first, second) =
                    transactor.inTransaction { transaction ->
                        insertRecord(transaction, "first application row") to insertRecord(transaction, "second application row")
                    }

                transactor.inTransaction { findRecord(it, first) } shouldBe "first application row"
                transactor.inTransaction { findRecord(it, second) } shouldBe "second application row"
                jdbi.count("testapp.test_application_records") shouldBe before + 2
            }

            test("a failure rolls back every write in the transaction and rethrows the original exception") {
                val before = jdbi.count("testapp.test_application_records")
                var written: UUID? = null

                shouldThrow<IllegalStateException> {
                    transactor.inTransaction { transaction ->
                        written = insertRecord(transaction, "first application row")
                        insertRecord(transaction, "second application row")
                        throw IllegalStateException("application policy rejected the operation")
                    }
                }.message shouldBe "application policy rejected the operation"

                jdbi.count("testapp.test_application_records") shouldBe before
                transactor.inTransaction { findRecord(it, written!!) }.shouldBeNull()
            }

            test("the block's result is returned") {
                transactor.inTransaction { 42 } shouldBe 42
            }
        }

        context("transaction isolation") {
            val transactor = Transactor(jdbi)
            val timeoutSeconds = 10L

            fun insertRecord(
                transaction: Transaction,
                id: UUID,
                value: String,
            ) {
                transaction.handle
                    .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, :value)")
                    .bind("id", id)
                    .bind("value", value)
                    .execute()
            }

            fun updateRecord(
                transaction: Transaction,
                id: UUID,
                value: String,
            ) {
                transaction.handle
                    .createUpdate("UPDATE testapp.test_application_records SET value = :value WHERE id = :id")
                    .bind("id", id)
                    .bind("value", value)
                    .execute()
            }

            // Stand-ins for repositories that receive the caller's Transaction and know nothing of its isolation.
            fun readValue(
                transaction: Transaction,
                id: UUID,
            ): String? =
                transaction.handle
                    .createQuery("SELECT value FROM testapp.test_application_records WHERE id = :id")
                    .bind("id", id)
                    .mapTo(String::class.java)
                    .findOne()
                    .orElse(null)

            fun effectiveIsolation(transaction: Transaction): String =
                transaction.handle
                    .createQuery("SELECT current_setting('transaction_isolation')")
                    .mapTo(String::class.java)
                    .one()

            fun sessionDefaultIsolation(transaction: Transaction): String =
                transaction.handle
                    .createQuery("SELECT current_setting('default_transaction_isolation')")
                    .mapTo(String::class.java)
                    .one()

            fun backendPid(transaction: Transaction): Int =
                transaction.handle
                    .createQuery("SELECT pg_backend_pid()")
                    .mapTo(Int::class.java)
                    .one()

            fun seed(value: String): UUID = UUID.randomUUID().also { id -> transactor.inTransaction { insertRecord(it, id, value) } }

            /**
             * A reader transaction at [isolation] reads the row, waits until a default-isolation writer has
             * updated it to "B" and committed, then reads again. Returns both reads.
             */
            fun readAcrossConcurrentCommit(isolation: TransactionIsolation?): Pair<String?, String?> {
                val id = seed("A")
                val firstReadDone = CountDownLatch(1)
                val writerCommitted = CountDownLatch(1)

                val reader =
                    CompletableFuture.supplyAsync {
                        val block = { transaction: Transaction ->
                            val first = readValue(transaction, id)
                            firstReadDone.countDown()
                            check(
                                writerCommitted.await(timeoutSeconds, TimeUnit.SECONDS),
                            ) { "the writer did not commit while the reader was open" }
                            first to readValue(transaction, id)
                        }
                        if (isolation == null) transactor.inTransaction(block) else transactor.inTransaction(isolation, block)
                    }

                try {
                    check(firstReadDone.await(timeoutSeconds, TimeUnit.SECONDS)) { "the reader never read" }
                    // The reader's transaction is still open, so this commit proves the reader does not block the writer.
                    transactor.inTransaction { updateRecord(it, id, "B") }
                } finally {
                    writerCommitted.countDown()
                }

                val reads = reader.get(timeoutSeconds, TimeUnit.SECONDS)
                transactor.inTransaction { readValue(it, id) } shouldBe "B"
                return reads
            }

            test("REPEATABLE_READ observes one snapshot while a writer commits, and does not block the writer") {
                readAcrossConcurrentCommit(TransactionIsolation.REPEATABLE_READ) shouldBe ("A" to "A")
            }

            test("the default is READ_COMMITTED: a later read sees the concurrent commit") {
                readAcrossConcurrentCommit(null) shouldBe ("A" to "B")
            }

            test("an explicit READ_COMMITTED behaves as the default") {
                readAcrossConcurrentCommit(TransactionIsolation.READ_COMMITTED) shouldBe ("A" to "B")
            }

            test("the requested level is in effect for the whole transaction") {
                transactor.inTransaction { effectiveIsolation(it) } shouldBe "read committed"
                transactor.inTransaction(TransactionIsolation.READ_COMMITTED) { effectiveIsolation(it) } shouldBe "read committed"
                transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { effectiveIsolation(it) } shouldBe "repeatable read"
            }

            test("caller-owned Transaction helpers share the outer transaction, its connection, and its isolation") {
                val id = seed("A")

                transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
                    val pid = backendPid(transaction)
                    readValue(transaction, id) shouldBe "A"
                    effectiveIsolation(transaction) shouldBe "repeatable read"
                    // An uncommitted write made through the same Transaction is visible to it alone.
                    updateRecord(transaction, id, "B")
                    readValue(transaction, id) shouldBe "B"
                    backendPid(transaction) shouldBe pid
                }
            }

            test("a failure rolls back at either level and rethrows the original exception") {
                TransactionIsolation.entries.forEach { isolation ->
                    val id = UUID.randomUUID()

                    shouldThrow<IllegalStateException> {
                        transactor.inTransaction(isolation) { transaction ->
                            insertRecord(transaction, id, "rolled back")
                            throw IllegalStateException("rejected at $isolation")
                        }
                    }.message shouldBe "rejected at $isolation"

                    transactor.inTransaction { readValue(it, id) }.shouldBeNull()
                }
            }

            test("isolation is scoped to one request: a reused pooled connection is READ_COMMITTED again") {
                // One connection in the pool, so every transaction below reuses the same physical connection.
                val configuration = database.configuration.copy(maximumPoolSize = 1, minimumIdle = 1)
                createDataSource(configuration, poolName = "isolation-pool").use { pool ->
                    val single = Transactor(Jdbi.create(pool))

                    val (pid, during) =
                        single.inTransaction(TransactionIsolation.REPEATABLE_READ) { backendPid(it) to effectiveIsolation(it) }
                    during shouldBe "repeatable read"

                    single.inTransaction { transaction ->
                        backendPid(transaction) shouldBe pid
                        effectiveIsolation(transaction) shouldBe "read committed"
                        sessionDefaultIsolation(transaction) shouldBe "read committed"
                    }

                    // A rolled-back repeatable-read transaction must not leak either.
                    shouldThrow<IllegalStateException> {
                        single.inTransaction(TransactionIsolation.REPEATABLE_READ) { throw IllegalStateException("rollback") }
                    }
                    single.inTransaction { transaction ->
                        backendPid(transaction) shouldBe pid
                        effectiveIsolation(transaction) shouldBe "read committed"
                        sessionDefaultIsolation(transaction) shouldBe "read committed"
                    }
                }
            }
            test("an explicit isolation is explicit: it overrides a pool whose baseline is another level") {
                // A pool whose connections default to REPEATABLE READ, unrelated to createDataSource's baseline.
                val baseline =
                    HikariDataSource(
                        HikariConfig().apply {
                            jdbcUrl = database.configuration.jdbcUrl
                            username = database.configuration.username
                            password = database.configuration.password
                            maximumPoolSize = 1
                            minimumIdle = 1
                            poolName = "repeatable-read-baseline"
                            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                        },
                    )
                baseline.use { pool ->
                    val other = Transactor(Jdbi.create(pool))

                    other.inTransaction { effectiveIsolation(it) } shouldBe "repeatable read"
                    other.inTransaction(TransactionIsolation.READ_COMMITTED) { effectiveIsolation(it) } shouldBe "read committed"
                    other.inTransaction(TransactionIsolation.REPEATABLE_READ) { effectiveIsolation(it) } shouldBe "repeatable read"
                    // The explicit request does not stick: the same connection returns to its baseline.
                    other.inTransaction(TransactionIsolation.READ_COMMITTED) { effectiveIsolation(it) } shouldBe "read committed"
                    other.inTransaction { effectiveIsolation(it) } shouldBe "repeatable read"
                }
            }
        }

        // JDBI joins a nested inTransaction on the same thread to the outer managed handle. These specs pin
        // that behaviour; the preferred composition is still to pass the caller-owned Transaction down.
        context("nested inTransaction") {
            val transactor = Transactor(jdbi)

            fun backendPid(transaction: Transaction): Int =
                transaction.handle
                    .createQuery("SELECT pg_backend_pid()")
                    .mapTo(Int::class.java)
                    .one()

            fun effectiveIsolation(transaction: Transaction): String =
                transaction.handle
                    .createQuery("SELECT current_setting('transaction_isolation')")
                    .mapTo(String::class.java)
                    .one()

            fun insertRecord(
                transaction: Transaction,
                id: UUID,
            ) {
                transaction.handle
                    .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, 'nested')")
                    .bind("id", id)
                    .execute()
            }

            fun exists(id: UUID): Boolean =
                transactor.inTransaction { transaction ->
                    transaction.handle
                        .createQuery("SELECT count(*) FROM testapp.test_application_records WHERE id = :id")
                        .bind("id", id)
                        .mapTo(Int::class.java)
                        .one() == 1
                }

            test("an inner call runs on the outer transaction's connection and transaction") {
                transactor.inTransaction { outer ->
                    val outerPid = backendPid(outer)
                    val outerXid =
                        outer.handle
                            .createQuery("SELECT txid_current()")
                            .mapTo(Long::class.java)
                            .one()

                    transactor.inTransaction { inner ->
                        backendPid(inner) shouldBe outerPid
                        inner.handle
                            .createQuery("SELECT txid_current()")
                            .mapTo(Long::class.java)
                            .one() shouldBe outerXid
                    }
                }
            }

            test("an inner call is not a separate transaction: when the outer fails, outer and inner writes roll back together") {
                val outerId = UUID.randomUUID()
                val innerId = UUID.randomUUID()

                shouldThrow<IllegalStateException> {
                    transactor.inTransaction { outer ->
                        insertRecord(outer, outerId)
                        transactor.inTransaction { inner -> insertRecord(inner, innerId) }
                        throw IllegalStateException("outer failed")
                    }
                }.message shouldBe "outer failed"

                exists(outerId) shouldBe false
                exists(innerId) shouldBe false
            }

            test("an inner call without an isolation inherits the outer transaction's isolation") {
                transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { outer ->
                    effectiveIsolation(outer) shouldBe "repeatable read"

                    transactor.inTransaction { inner -> effectiveIsolation(inner) shouldBe "repeatable read" }
                }
                transactor.inTransaction(TransactionIsolation.READ_COMMITTED) { outer ->
                    transactor.inTransaction { inner -> effectiveIsolation(inner) shouldBe "read committed" }
                }
            }

            test("an inner call may repeat the outer transaction's isolation") {
                TransactionIsolation.entries.forEach { isolation ->
                    transactor.inTransaction(isolation) { outer ->
                        transactor.inTransaction(isolation) { inner ->
                            backendPid(inner) shouldBe backendPid(outer)
                            effectiveIsolation(inner) shouldBe effectiveIsolation(outer)
                        }
                    }
                }
            }

            test("an inner call requesting a different isolation than the open transaction fails") {
                val conflicts =
                    listOf(
                        // outer request to inner request; the default outer transaction is READ COMMITTED
                        TransactionIsolation.REPEATABLE_READ to TransactionIsolation.READ_COMMITTED,
                        TransactionIsolation.READ_COMMITTED to TransactionIsolation.REPEATABLE_READ,
                    )
                conflicts.forEach { (outerIsolation, innerIsolation) ->
                    val id = UUID.randomUUID()

                    shouldThrow<TransactionException> {
                        transactor.inTransaction(outerIsolation) { outer ->
                            insertRecord(outer, id)
                            transactor.inTransaction(innerIsolation) { }
                        }
                    }.message shouldContain "nested transaction with isolation level"

                    // The failure escaped the outer block, so the outer transaction rolled back.
                    exists(id) shouldBe false
                }

                shouldThrow<TransactionException> {
                    transactor.inTransaction { transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { } }
                }
            }

            test("a rejected inner isolation change leaves the outer transaction usable and its isolation unchanged") {
                transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { outer ->
                    shouldThrow<TransactionException> {
                        transactor.inTransaction(TransactionIsolation.READ_COMMITTED) { }
                    }

                    effectiveIsolation(outer) shouldBe "repeatable read"
                }
            }
        }
    })
