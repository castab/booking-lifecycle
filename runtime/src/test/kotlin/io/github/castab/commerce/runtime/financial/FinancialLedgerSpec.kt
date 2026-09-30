package io.github.castab.commerce.runtime.financial

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.ExternalPaymentReference
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.PostgresFinancialDocumentRepository
import io.github.castab.commerce.runtime.persistence.PostgresPaymentRepository
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.persistence.createDataSource
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.testApplicationMigrations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.SqlLogger
import org.jdbi.v3.core.statement.StatementContext
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private val usd = Currency.getInstance("USD")
private val eur = Currency.getInstance("EUR")

private fun money(
    amount: String,
    currency: Currency = usd,
) = Money(BigDecimal(amount), currency)

private fun line(
    price: String = "1000.00",
    quantity: String? = null,
    subDescription: String? = null,
    id: UUID = UUID.randomUUID(),
) = LineItem(id, "Service", subDescription, quantity?.toBigDecimal(), money(price), money("0.00"))

class FinancialLedgerSpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var dataSource: com.zaxxer.hikari.HikariDataSource
        lateinit var transactor: Transactor
        val documents = PostgresFinancialDocumentRepository()
        val payments = PostgresPaymentRepository(documents)
        lateinit var ledger: FinancialLedger

        beforeSpec {
            database = TestDatabase.create()
            dataSource = createDataSource(database.configuration, "financial-ledger-spec")
            MigrationLifecycle(dataSource, testApplicationMigrations()).migrate()
            transactor = Transactor(Jdbi.create(dataSource))
            ledger = FinancialLedger(transactor, documents, payments)
        }

        afterSpec {
            dataSource.close()
            database.close()
        }

        test("all entry stages and ordered exact decimal lines round-trip without stored totals") {
            val first = line("12.3400", "2.500", "secondary")
            val second = line("3.00", null, null)
            listOf<(UUID) -> FinancialDocument>(
                { FinancialDocument.Estimate.create(it, listOf(first, second)) },
                { FinancialDocument.Quote.create(it, listOf(first, second)) },
                { FinancialDocument.Invoice.create(it, listOf(first, second)) },
            ).forEach { create ->
                val original = create(UUID.randomUUID())
                ledger.create(original)
                val restored = ledger.get(original.reference)
                restored shouldBe original
                restored.lineItems.shouldContainExactly(first, second)
                restored.total shouldBe original.total
                ledger.latest(original.id) shouldBe original
                ledger.history(original.id).shouldContainExactly(original)
            }
            transactor.inTransaction { transaction ->
                transaction.handle
                    .createQuery(
                        """SELECT count(*) FROM information_schema.columns
                           WHERE table_schema = 'commerce' AND table_name = 'financial_document_snapshots'
                           AND column_name IN ('total', 'subtotal', 'currency', 'balance')""",
                    ).mapTo(Int::class.java)
                    .one() shouldBe 0
            }
        }

        test("stage transitions and change orders append immutable history") {
            val id = UUID.randomUUID()
            val first = ledger.create(FinancialDocument.Estimate.create(id, listOf(line())))
            val changedEstimate = ledger.changeOrder(id, ChangeOrder(listOf(ChangeOrder.Change.AddLineItem(line("1.25")))))
            changedEstimate shouldBe ledger.get(FinancialDocumentReference(id, Version.of(2)))
            val quote = ledger.issueQuote(id)
            quote.version shouldBe Version.of(3)
            val changedQuote = ledger.changeOrder(id, ChangeOrder(listOf(ChangeOrder.Change.AddLineItem(line("2.00")))))
            val invoice = ledger.issueInvoice(id)
            invoice.version shouldBe Version.of(5)
            val changedInvoice = ledger.changeOrder(id, ChangeOrder(listOf(ChangeOrder.Change.AddLineItem(line("3.00")))))
            ledger.history(id).shouldContainExactly(first, changedEstimate, quote, changedQuote, invoice, changedInvoice)
            val versions = ledger.versionHistory(id)
            versions.map { it.document }.shouldContainExactly(first, changedEstimate, quote, changedQuote, invoice, changedInvoice)
            versions.map { it.document.reference }.distinct().size shouldBe 6
            versions.forEach { stored ->
                val reread = ledger.version(stored.document.reference)
                reread.document shouldBe stored.document
                reread.createdAt shouldBe stored.createdAt
            }
            ledger.latestVersion(id).document shouldBe changedInvoice
            ledger.latestVersion(id).createdAt shouldBe versions.last().createdAt
            shouldThrow<CommerceFailure.IllegalTransition> { ledger.issueQuote(id) }
            shouldThrow<CommerceFailure.IllegalTransition> { ledger.issueInvoice(id) }
            ledger.history(id).size shouldBe 6
        }

        test("metadata reads restore each document only once") {
            val first = ledger.create(FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(line())))
            ledger.issueQuote(first.id)
            ledger.issueInvoice(first.id)
            val statements = mutableListOf<String>()
            val probe =
                object : SqlLogger {
                    override fun logBeforeExecution(context: StatementContext) {
                        statements += context.renderedSql.lowercase()
                    }
                }
            val probedLedger = FinancialLedger(Transactor(Jdbi.create(dataSource).setSqlLogger(probe)), documents, payments)

            fun assertReads(
                expectedLineQueries: Int,
                block: () -> Unit,
            ) {
                statements.clear()
                block()
                statements.count { "from commerce.financial_document_snapshots" in it } shouldBe 1
                statements.count { "from commerce.financial_document_lines" in it } shouldBe expectedLineQueries
            }

            assertReads(1) { probedLedger.version(first.reference) }
            assertReads(1) { probedLedger.latestVersion(first.id) }
            assertReads(3) { probedLedger.versionHistory(first.id) }
            assertReads(3) { probedLedger.history(first.id) }
        }

        test("two successors computed from one source cannot both commit") {
            val source = FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(line()))
            ledger.create(source)
            val successor = source.toQuote()
            val executor = Executors.newFixedThreadPool(2)
            try {
                val outcomes =
                    listOf(1, 2)
                        .map {
                            executor.submit(
                                Callable {
                                    runCatching { transactor.inTransaction { transaction -> documents.insert(transaction, successor) } }
                                },
                            )
                        }.map { it.get() }
                outcomes.count { it.isSuccess } shouldBe 1
                outcomes.count { it.exceptionOrNull() is CommerceFailure.Conflict } shouldBe 1
                ledger.history(source.id).shouldContainExactly(source, successor)
            } finally {
                executor.shutdownNow()
            }
        }

        test("payments, exact snapshot allocations, and lineage balance") {
            val id = UUID.randomUUID()
            ledger.create(FinancialDocument.Estimate.create(id, listOf(line())))
            val quote = ledger.issueQuote(id)
            val payment =
                PaymentRecord(
                    UUID.randomUUID(),
                    money("300.00"),
                    PaymentMethod.CARD,
                    Instant.parse("2026-01-01T00:00:00.123456789Z"),
                    ExternalPaymentReference("processor", UUID.randomUUID().toString()),
                )
            val allocation =
                ledger.recordPaymentAgainstDocument(
                    payment,
                    UUID.randomUUID(),
                    quote.reference,
                    money("300.00"),
                    Instant.parse("2026-01-02T00:00:00.987654321Z"),
                )
            transactor.inTransaction { transaction ->
                payments.retrievePayment(transaction, payment.id) shouldBe payment
                payments.retrieveAllocation(transaction, allocation.id) shouldBe allocation
            }
            val invoice = ledger.issueInvoice(id)
            val firstBalance = ledger.reconcileLatest(id)
            firstBalance.documentReference shouldBe invoice.reference
            firstBalance.documentTotal shouldBe money("1000.00")
            firstBalance.grossAllocated shouldBe money("300.00")
            firstBalance.netApplied shouldBe money("300.00")
            firstBalance.balance shouldBe money("700.00")
            allocation.financialDocumentReference shouldBe quote.reference
            ledger.changeOrder(
                id,
                ChangeOrder(
                    listOf(
                        ChangeOrder.Change.ReplaceLineItem(
                            invoice.lineItems.single().id,
                            line("1200.00", id = invoice.lineItems.single().id),
                        ),
                    ),
                ),
            )
            ledger.reconcileLatest(id).balance shouldBe money("900.00")
            transactor.inTransaction { transaction -> payments.retrieveAllocation(transaction, allocation.id) } shouldBe allocation
            shouldThrow<CommerceFailure.Conflict> {
                ledger.recordPayment(payment.copyWithNewId())
            }
        }

        test("unapplied and split payments respect the complete allocation history") {
            val a = ledger.create(FinancialDocument.Invoice.create(UUID.randomUUID(), listOf(line())))
            val b = ledger.create(FinancialDocument.Invoice.create(UUID.randomUUID(), listOf(line())))
            val payment = PaymentRecord(UUID.randomUUID(), money("500.00"), PaymentMethod.CASH, Instant.EPOCH)
            ledger.recordPayment(payment)
            val first = ledger.allocatePayment(payment.id, UUID.randomUUID(), a.reference, money("300.00"), Instant.EPOCH)
            shouldThrow<CommerceFailure.InvariantViolated> {
                ledger.allocatePayment(payment.id, UUID.randomUUID(), b.reference, money("250.00"), Instant.EPOCH)
            }
            shouldThrow<CommerceFailure.ValidationFailed> {
                ledger.allocatePayment(payment.id, UUID.randomUUID(), b.reference, money("10.00", eur), Instant.EPOCH)
            }
            shouldThrow<CommerceFailure.NotFound> {
                ledger.allocatePayment(
                    payment.id,
                    UUID.randomUUID(),
                    FinancialDocumentReference(UUID.randomUUID(), Version.INITIAL),
                    money("1.00"),
                    Instant.EPOCH,
                )
            }
            val second = ledger.allocatePayment(payment.id, UUID.randomUUID(), b.reference, money("200.00"), Instant.EPOCH)
            transactor.inTransaction { payments.allocationsForPayment(it, payment.id) }.toSet() shouldBe setOf(first, second)
        }

        test("concurrent allocations of the same payment cannot spend it twice") {
            val document = ledger.create(FinancialDocument.Invoice.create(UUID.randomUUID(), listOf(line())))
            val payment = ledger.recordPayment(PaymentRecord(UUID.randomUUID(), money("500.00"), PaymentMethod.CARD, Instant.EPOCH))
            val executor = Executors.newFixedThreadPool(2)
            try {
                val outcomes =
                    listOf(1, 2)
                        .map {
                            executor.submit(
                                Callable {
                                    runCatching {
                                        ledger.allocatePayment(
                                            payment.id,
                                            UUID.randomUUID(),
                                            document.reference,
                                            money("300.00"),
                                            Instant.EPOCH,
                                        )
                                    }
                                },
                            )
                        }.map { it.get() }
                outcomes.count { it.isSuccess } shouldBe 1
                outcomes.count { it.exceptionOrNull() is CommerceFailure.InvariantViolated } shouldBe 1
                transactor.inTransaction { payments.allocationsForPayment(it, payment.id) }.size shouldBe 1
            } finally {
                executor.shutdownNow()
            }
        }

        test("a quote transition and application write commit or roll back together") {
            val estimate = ledger.create(FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(line())))
            val rolledBackRow = UUID.randomUUID()
            shouldThrow<IllegalStateException> {
                transactor.inTransaction { transaction ->
                    val quote = ledger.issueQuote(transaction, estimate.id)
                    ledger.get(transaction, quote.reference) shouldBe quote
                    ledger.latest(transaction, estimate.id) shouldBe quote
                    ledger.history(transaction, estimate.id).shouldContainExactly(estimate, quote)
                    transaction.handle
                        .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, :value)")
                        .bind("id", rolledBackRow)
                        .bind("value", "quote issued")
                        .execute()
                    error("abort")
                }
            }
            ledger.latest(estimate.id) shouldBe estimate
            transactor.inTransaction { transaction ->
                transaction.handle
                    .createQuery("SELECT count(*) FROM testapp.test_application_records WHERE id = :id")
                    .bind("id", rolledBackRow)
                    .mapTo(Int::class.java)
                    .one()
            } shouldBe 0

            val committedRow = UUID.randomUUID()
            val quote =
                transactor.inTransaction { transaction ->
                    ledger.issueQuote(transaction, estimate.id).also {
                        transaction.handle
                            .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, :value)")
                            .bind("id", committedRow)
                            .bind("value", "quote issued")
                            .execute()
                    }
                }
            ledger.latest(estimate.id) shouldBe quote
            transactor.inTransaction { transaction ->
                transaction.handle
                    .createQuery("SELECT value FROM testapp.test_application_records WHERE id = :id")
                    .bind("id", committedRow)
                    .mapTo(String::class.java)
                    .one()
            } shouldBe "quote issued"
        }

        test("a payment, allocation, and application receipt commit or roll back together") {
            val document = ledger.create(FinancialDocument.Invoice.create(UUID.randomUUID(), listOf(line())))
            val rolledBackPayment = PaymentRecord(UUID.randomUUID(), money("20.00"), PaymentMethod.CARD, Instant.EPOCH)
            val rolledBackAllocation = UUID.randomUUID()
            val rolledBackRow = UUID.randomUUID()
            shouldThrow<IllegalStateException> {
                transactor.inTransaction { transaction ->
                    ledger.recordPaymentAgainstDocument(
                        transaction,
                        rolledBackPayment,
                        rolledBackAllocation,
                        document.reference,
                        money("20.00"),
                        Instant.EPOCH,
                    )
                    transaction.handle
                        .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, :value)")
                        .bind("id", rolledBackRow)
                        .bind("value", "provider receipt")
                        .execute()
                    error("abort")
                }
            }
            transactor.inTransaction { transaction ->
                payments.retrievePayment(transaction, rolledBackPayment.id) shouldBe null
                payments.retrieveAllocation(transaction, rolledBackAllocation) shouldBe null
                transaction.handle
                    .createQuery("SELECT count(*) FROM testapp.test_application_records WHERE id = :id")
                    .bind("id", rolledBackRow)
                    .mapTo(Int::class.java)
                    .one() shouldBe 0
            }

            val committedPayment = PaymentRecord(UUID.randomUUID(), money("30.00"), PaymentMethod.CARD, Instant.EPOCH)
            val committedRow = UUID.randomUUID()
            val allocation =
                transactor.inTransaction { transaction ->
                    ledger
                        .recordPaymentAgainstDocument(
                            transaction,
                            committedPayment,
                            UUID.randomUUID(),
                            document.reference,
                            money("30.00"),
                            Instant.EPOCH,
                        ).also {
                            transaction.handle
                                .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, :value)")
                                .bind("id", committedRow)
                                .bind("value", "provider receipt")
                                .execute()
                        }
                }
            transactor.inTransaction { transaction ->
                payments.retrievePayment(transaction, committedPayment.id) shouldBe committedPayment
                payments.retrieveAllocation(transaction, allocation.id) shouldBe allocation
                ledger.reconcileLatest(transaction, document.id).netApplied shouldBe money("30.00")
                ledger.reconcile(transaction, document.reference).netApplied shouldBe money("30.00")
                transaction.handle
                    .createQuery("SELECT value FROM testapp.test_application_records WHERE id = :id")
                    .bind("id", committedRow)
                    .mapTo(String::class.java)
                    .one() shouldBe "provider receipt"
            }
        }

        test("the database rejects an unsupported payment method") {
            shouldThrow<UnableToExecuteStatementException> {
                transactor.inTransaction { transaction ->
                    transaction.handle
                        .createUpdate(
                            """INSERT INTO commerce.payment_records
                               (payment_id, amount, currency, method, received_at_seconds, received_at_nanos)
                               VALUES (:id, :amount, :currency, :method, :seconds, :nanos)""",
                        ).bind("id", UUID.randomUUID())
                        .bind("amount", BigDecimal("1.00"))
                        .bind("currency", "USD")
                        .bind("method", "UNSUPPORTED")
                        .bind("seconds", 0L)
                        .bind("nanos", 0)
                        .execute()
                }
            }
        }

        test("payment and allocation roll back together, and application rows share document transaction") {
            val document = FinancialDocument.Invoice.create(UUID.randomUUID(), listOf(line()))
            ledger.create(document)
            val payment = PaymentRecord(UUID.randomUUID(), money("5.00"), PaymentMethod.CASH, Instant.EPOCH)
            shouldThrow<CommerceFailure.ValidationFailed> {
                ledger.recordPaymentAgainstDocument(payment, UUID.randomUUID(), document.reference, money("1.00", eur), Instant.EPOCH)
            }
            transactor.inTransaction { payments.retrievePayment(it, payment.id) } shouldBe null

            val associated = FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(line()))
            shouldThrow<IllegalStateException> {
                transactor.inTransaction { transaction ->
                    ledger.create(transaction, associated)
                    transaction.handle
                        .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, :value)")
                        .bind("id", UUID.randomUUID())
                        .bind("value", "related")
                        .execute()
                    error("abort")
                }
            }
            shouldThrow<CommerceFailure.NotFound> { ledger.get(associated.reference) }

            val committed = FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(line()))
            val rowId = UUID.randomUUID()
            transactor.inTransaction { transaction ->
                ledger.create(transaction, committed)
                transaction.handle
                    .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, :value)")
                    .bind("id", rowId)
                    .bind("value", "associated")
                    .execute()
            }
            ledger.get(committed.reference) shouldBe committed
            transactor.inTransaction { transaction ->
                transaction.handle
                    .createQuery("SELECT value FROM testapp.test_application_records WHERE id = :id")
                    .bind("id", rowId)
                    .mapTo(String::class.java)
                    .one()
            } shouldBe "associated"
        }
    })

private fun PaymentRecord.copyWithNewId() = PaymentRecord(UUID.randomUUID(), amount, method, receivedAt, externalReference)
