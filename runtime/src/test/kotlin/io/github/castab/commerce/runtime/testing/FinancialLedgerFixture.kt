package io.github.castab.commerce.runtime.testing

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.PostgresFinancialDocumentRepository
import io.github.castab.commerce.runtime.persistence.PostgresPaymentRepository
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.persistence.createDataSource
import io.kotest.matchers.shouldBe
import org.jdbi.v3.core.Jdbi
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun depositMoney(
    value: String,
    currency: String = "USD",
): Money = Money(BigDecimal(value), Currency.getInstance(currency))

internal class FinancialLedgerFixture : AutoCloseable {
    private val database = TestDatabase.create()
    val dataSource = createDataSource(database.configuration, "deposit-ledger-test")
    val documents = PostgresFinancialDocumentRepository()
    val payments = PostgresPaymentRepository(documents)
    val transactor = Transactor(Jdbi.create(dataSource))
    val ledger = FinancialLedger(transactor, documents, payments)

    init {
        MigrationLifecycle(dataSource, testApplicationMigrations()).migrate()
    }

    fun document(total: String = "100.00"): FinancialDocument.Quote =
        FinancialDocument.Quote
            .create(
                UUID.randomUUID(),
                listOf(LineItem(UUID.randomUUID(), "Service", null, null, depositMoney(total), depositMoney("0"))),
            ).also { ledger.create(it) }

    fun payment(
        amount: String = "100.00",
        receivedAt: Instant = Instant.EPOCH,
    ): PaymentRecord =
        PaymentRecord(UUID.randomUUID(), depositMoney(amount), PaymentMethod.CASH, receivedAt).also { ledger.recordPayment(it) }

    fun <T> onOtherThread(block: () -> T): T =
        Executors.newSingleThreadExecutor().use { it.submit(Callable(block)).get(30, TimeUnit.SECONDS) }

    /** The contender must finish while the lineage holder is still uncommitted. */
    fun <T> contendForLineage(
        holding: (Transaction) -> Unit,
        waiting: (Transaction) -> T,
        rollbackHolder: Boolean = false,
    ): Result<T> {
        val executor = Executors.newFixedThreadPool(2)
        val release = CountDownLatch(1)
        try {
            val holderReady = CompletableFuture<Unit>()
            val holder =
                executor.submit(
                    Callable {
                        runCatching {
                            transactor.inTransaction { transaction ->
                                holding(transaction)
                                holderReady.complete(Unit)
                                check(release.await(30, TimeUnit.SECONDS))
                                if (rollbackHolder) error("abort holder")
                            }
                        }.also { if (!holderReady.isDone) holderReady.completeExceptionally(it.exceptionOrNull()!!) }
                    },
                )
            holderReady.get(30, TimeUnit.SECONDS)
            val waiter =
                executor.submit(
                    Callable {
                        runCatching {
                            transactor.inTransaction { transaction ->
                                waiting(transaction)
                            }
                        }
                    },
                )
            val outcome = waiter.get(10, TimeUnit.SECONDS)
            holder.isDone shouldBe false
            release.countDown()
            holder.get(30, TimeUnit.SECONDS).isFailure shouldBe rollbackHolder
            return outcome
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    /** Observes an actual PostgreSQL wait edge; polling does not decide operation ordering. */
    fun awaitBlocked(
        waiter: Int,
        holder: Int,
    ): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            val query =
                transactor.inTransaction { transaction ->
                    transaction.handle
                        .createQuery(
                            """SELECT query FROM pg_stat_activity
                       WHERE pid = :waiter AND wait_event_type = 'Lock'
                         AND :holder = ANY (pg_blocking_pids(pid))""",
                        ).bind("waiter", waiter)
                        .bind("holder", holder)
                        .mapTo(String::class.java)
                        .findOne()
                        .orElse(null)
                }
            if (query != null) return query
            check(System.nanoTime() < deadline) { "Expected PostgreSQL lock wait was not observed" }
            Thread.yield()
        }
    }

    fun pid(transaction: Transaction): Int =
        transaction.handle
            .createQuery("SELECT pg_backend_pid()")
            .mapTo(Int::class.java)
            .one()

    override fun close() {
        dataSource.close()
        database.close()
    }
}
