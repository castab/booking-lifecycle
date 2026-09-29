package io.github.castab.commerce.runtime.financial

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.PaymentAllocation
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentReconciliation
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.payment.RefundAllocation
import io.github.castab.commerce.payment.RefundRecord
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.PostgresFinancialDocumentRepository
import io.github.castab.commerce.runtime.persistence.PostgresPaymentRepository
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.persistence.createDataSource
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.testApplicationMigrations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.SqlLogger
import org.jdbi.v3.core.statement.StatementContext
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val usd = Currency.getInstance("USD")

private fun money(amount: String) = Money(BigDecimal(amount), usd)

/** Compares numerically, because `Money` equality is scale-sensitive and derived sums start at zero. */
private infix fun Money.shouldBeNumerically(expected: String) {
    withClue("$this should be numerically $expected") {
        currency shouldBe usd
        amount.compareTo(BigDecimal(expected)) shouldBe 0
    }
}

private fun at(second: Long): Instant = Instant.ofEpochSecond(1_800_000_000L + second)

private fun PaymentHistory.copyOfFacts() = Triple(allocations.toList(), refunds.toList(), refundAllocations.toList())

private fun reconcileIndependently(history: PaymentHistory): PaymentReconciliation =
    PaymentReconciliation.reconcile(history.payment, history.allocations, emptyList(), history.refunds, history.refundAllocations)

/**
 * Runs [block] on another thread, so its transactions are independent of the calling thread's:
 * `Transactor.inTransaction` on the same thread joins the transaction that is already open.
 */
private fun <T> onOtherThread(block: () -> T): T = CompletableFuture.supplyAsync(block).get(30, TimeUnit.SECONDS)

/**
 * Payment history reads of the financial ledger against a real PostgreSQL: rediscovering the
 * facts of a payment and of a document lineage after their mutation results are gone.
 */
class FinancialLedgerPaymentHistorySpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var dataSource: com.zaxxer.hikari.HikariDataSource
        lateinit var transactor: Transactor
        val documents = PostgresFinancialDocumentRepository()
        val payments = PostgresPaymentRepository(documents)
        lateinit var ledger: FinancialLedger

        beforeSpec {
            database = TestDatabase.create()
            dataSource = createDataSource(database.configuration, "financial-ledger-payment-history-spec")
            MigrationLifecycle(dataSource, testApplicationMigrations()).migrate()
            transactor = Transactor(Jdbi.create(dataSource))
            ledger = FinancialLedger(transactor, documents, payments)
        }

        afterSpec {
            dataSource.close()
            database.close()
        }

        fun invoice(total: String = "1000.00"): FinancialDocument =
            ledger.create(
                FinancialDocument.Invoice.create(
                    UUID.randomUUID(),
                    listOf(LineItem(UUID.randomUUID(), "Service", null, null, money(total), money("0.00"))),
                ),
            )

        fun nextVersion(document: FinancialDocument): FinancialDocument =
            ledger.changeOrder(
                document.id,
                ChangeOrder(
                    listOf(ChangeOrder.Change.AddLineItem(LineItem(UUID.randomUUID(), "Extra", null, null, money("1.00"), money("0.00")))),
                ),
            )

        fun payment(
            amount: String = "500.00",
            receivedAt: Instant = at(0),
        ): PaymentRecord = ledger.recordPayment(PaymentRecord(UUID.randomUUID(), money(amount), PaymentMethod.CARD, receivedAt))

        fun allocate(
            payment: PaymentRecord,
            document: FinancialDocument,
            amount: String,
            allocatedAt: Instant = at(0),
        ): PaymentAllocation = ledger.allocatePayment(payment.id, UUID.randomUUID(), document.reference, money(amount), allocatedAt)

        fun refund(
            payment: PaymentRecord,
            amount: String,
            refundedAt: Instant = at(0),
            vararg unwinds: Pair<UUID, String>,
        ): RecordedRefund =
            ledger.recordRefund(
                payment.id,
                UUID.randomUUID(),
                money(amount),
                PaymentMethod.CARD,
                refundedAt,
                null,
                unwinds.map { (allocationId, unwound) ->
                    RefundAllocationPortion(UUID.randomUUID(), allocationId, money(unwound), refundedAt)
                },
            )

        context("a payment by id") {
            test("an unallocated payment has no other facts and is entirely unapplied") {
                val payment = payment()

                val history = ledger.paymentHistory(payment.id)

                history.payment shouldBe payment
                history.allocations.shouldBeEmpty()
                history.refunds.shouldBeEmpty()
                history.refundAllocations.shouldBeEmpty()
                history.reconciliation shouldBe ledger.reconcilePayment(payment.id)
                history.reconciliation.paymentAmount shouldBeNumerically "500"
                history.reconciliation.grossAllocated shouldBeNumerically "0"
                history.reconciliation.unallocated shouldBeNumerically "500"
            }

            test("an unknown payment is not found") {
                shouldThrow<CommerceFailure.NotFound> { ledger.paymentHistory(UUID.randomUUID()) }
            }

            test("a partial allocation is rediscoverable with its exact snapshot") {
                val original = invoice()
                val document = nextVersion(original)
                val payment = payment()
                val allocation = allocate(payment, document, "200.00")

                val history = ledger.paymentHistory(payment.id)

                history.allocations.shouldContainExactly(allocation)
                history.allocations.single().financialDocumentReference shouldBe FinancialDocumentReference(original.id, Version.of(2))
                history.reconciliation.grossAllocated shouldBeNumerically "200"
                history.reconciliation.netAllocated shouldBeNumerically "200"
                history.reconciliation.unallocated shouldBeNumerically "300"
            }

            test("a fully allocated payment has nothing unapplied") {
                val payment = payment()
                allocate(payment, invoice(), "500.00")

                val reconciliation = ledger.paymentHistory(payment.id).reconciliation

                reconciliation.grossAllocated shouldBeNumerically "500"
                reconciliation.unallocated shouldBeNumerically "0"
            }

            test("a refund of allocated value is rediscoverable with every id and link") {
                val document = invoice()
                val payment = payment()
                val allocation = allocate(payment, document, "500.00")
                val recorded = refund(payment, "100.00", at(5), allocation.id to "100.00")

                // Everything below uses only the payment id, as an application does after a reload.
                val history = ledger.paymentHistory(payment.id)

                history.payment.id shouldBe payment.id
                history.allocations.map { it.id } shouldContainExactly listOf(allocation.id)
                history.refunds.shouldContainExactly(recorded.refund)
                history.refundAllocations.shouldContainExactly(recorded.allocations)
                history.refundAllocations.single().refundReference shouldBe history.refunds.single().id
                history.refundAllocations.single().paymentAllocationReference shouldBe history.allocations.single().id
                history.reconciliation.totalRefunded shouldBeNumerically "100"
                history.reconciliation.netReceived shouldBeNumerically "400"
                history.reconciliation.grossAllocated shouldBeNumerically "500"
                history.reconciliation.refundAllocations shouldBeNumerically "100"
                history.reconciliation.netAllocated shouldBeNumerically "400"
                history.reconciliation.unallocated shouldBeNumerically "0"
            }

            test("a refund from unapplied value has no refund allocation") {
                val payment = payment()
                allocate(payment, invoice(), "300.00")
                val recorded = refund(payment, "150.00")

                val history = ledger.paymentHistory(payment.id)

                history.refunds.shouldContainExactly(recorded.refund)
                history.refundAllocations.shouldBeEmpty()
                history.reconciliation.netReceived shouldBeNumerically "350"
                history.reconciliation.netAllocated shouldBeNumerically "300"
                history.reconciliation.unallocated shouldBeNumerically "50"
            }

            test("a refund split between applied and unapplied value agrees with its reconciliation") {
                val payment = payment()
                val allocation = allocate(payment, invoice(), "300.00")
                val recorded = refund(payment, "250.00", at(1), allocation.id to "50.00")

                val history = ledger.paymentHistory(payment.id)

                history.refunds.shouldContainExactly(recorded.refund)
                history.refundAllocations.single().amount shouldBeNumerically "50"
                history.reconciliation.totalRefunded shouldBeNumerically "250"
                history.reconciliation.refundAllocations shouldBeNumerically "50"
                history.reconciliation.netReceived shouldBeNumerically "250"
                history.reconciliation.netAllocated shouldBeNumerically "250"
                history.reconciliation.unallocated shouldBeNumerically "0"
            }

            test("collections are ordered by time, then id, whatever the insertion order") {
                val document = invoice("10000.00")
                val payment = payment("1000.00")
                val allocations =
                    listOf(at(30) to "10.00", at(10) to "20.00", at(20) to "30.00", at(10) to "40.00", at(30) to "50.00")
                        .map { (time, amount) -> allocate(payment, document, amount, time) }
                val refunds =
                    listOf(at(9), at(3), at(3), at(7)).map { time ->
                        refund(payment, "1.00", time, allocations.first().id to "1.00")
                    }

                val history = ledger.paymentHistory(payment.id)

                history.allocations shouldContainExactly
                    allocations.sortedWith(compareBy<PaymentAllocation> { it.allocatedAt }.thenBy { it.id })
                history.refunds shouldContainExactly refunds.map { it.refund }.sortedWith(compareBy({ it.refundedAt }, { it.id }))
                history.refundAllocations shouldContainExactly
                    refunds.flatMap { it.allocations }.sortedWith(compareBy({ it.allocatedAt }, { it.id }))
                history.refunds.map { it.refundedAt } shouldContainExactly listOf(at(3), at(3), at(7), at(9))
            }
        }

        context("payments of a document lineage") {
            test("one query discovers payments allocated to any version, ordered by receipt time") {
                val first = invoice()
                val second = nextVersion(first)
                val other = invoice()
                val later = payment(receivedAt = at(50))
                val earlier = payment(receivedAt = at(10))
                val unrelated = payment(receivedAt = at(1))
                val unallocated = payment(receivedAt = at(2))
                allocate(later, second, "100.00")
                allocate(earlier, first, "100.00")
                allocate(unrelated, other, "100.00")

                val histories = ledger.paymentHistoriesForLineage(first.id)

                histories.map { it.payment } shouldContainExactly listOf(earlier, later)
                histories[0].allocations.single().financialDocumentReference shouldBe first.reference
                histories[1].allocations.single().financialDocumentReference shouldBe second.reference
                histories.map { it.payment.id }.contains(unallocated.id) shouldBe false
                histories.forEach { it shouldBe ledger.paymentHistory(it.payment.id) }
            }

            test("payments received at the same instant are ordered by payment id") {
                val document = invoice()
                // Inserted in descending id order, so neither insertion nor allocation order is the id order.
                val ids = List(4) { UUID.randomUUID() }.sorted()
                ids.reversed().forEach { id ->
                    val payment = ledger.recordPayment(PaymentRecord(id, money("100.00"), PaymentMethod.CARD, at(7)))
                    allocate(payment, document, "10.00")
                }

                ledger.paymentHistoriesForLineage(document.id).map { it.payment.id } shouldContainExactly ids
            }

            test("several allocations of one payment yield one history") {
                val document = invoice()
                val payment = payment()
                allocate(payment, document, "100.00")
                allocate(payment, nextVersion(document), "100.00")

                val histories = ledger.paymentHistoriesForLineage(document.id)

                histories.size shouldBe 1
                histories.single().allocations.size shouldBe 2
            }

            test("a payment whose allocation was completely unwound is still discovered") {
                val document = invoice()
                val payment = payment()
                val allocation = allocate(payment, document, "200.00")
                refund(payment, "200.00", at(1), allocation.id to "200.00")

                val histories = ledger.paymentHistoriesForLineage(document.id)

                histories.map { it.payment } shouldContainExactly listOf(payment)
                histories.single().reconciliation.netAllocated shouldBeNumerically "0"
                histories.single().allocations.shouldContainExactly(allocation)
                ledger.reconcileLatest(document.id).netApplied shouldBeNumerically "0"
            }

            test("a split payment is returned whole, not filtered to the requested document") {
                val a = invoice()
                val b = invoice()
                val payment = payment()
                val toA = allocate(payment, a, "200.00", at(1))
                val toB = allocate(payment, b, "150.00", at(2))

                val forA = ledger.paymentHistoriesForLineage(a.id).single()

                forA.payment shouldBe payment
                forA.allocations.shouldContainExactly(toA, toB)
                forA.reconciliation shouldBe ledger.reconcilePayment(payment.id)
                forA.reconciliation.grossAllocated shouldBeNumerically "350"
                forA.reconciliation.unallocated shouldBeNumerically "150"
                forA.allocations.filter { it.financialDocumentReference.id == a.id }.shouldContainExactly(toA)
                ledger.paymentHistoriesForLineage(b.id).single() shouldBe forA
            }

            test("a document with no payments yields an empty list, and a missing one is not found") {
                ledger.paymentHistoriesForLineage(invoice().id).shouldBeEmpty()
                shouldThrow<CommerceFailure.NotFound> { ledger.paymentHistoriesForLineage(UUID.randomUUID()) }
            }
        }

        context("caller-owned transactions") {
            test("the transaction overloads read facts written earlier in the same uncommitted transaction") {
                val document = invoice()
                val paymentId = UUID.randomUUID()
                val allocationId = UUID.randomUUID()

                transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
                    ledger.recordPayment(transaction, PaymentRecord(paymentId, money("80.00"), PaymentMethod.CASH, at(0)))
                    ledger.allocatePayment(transaction, paymentId, allocationId, document.reference, money("30.00"), at(1))

                    val history = ledger.paymentHistory(transaction, paymentId)
                    history.allocations.map { it.id } shouldContainExactly listOf(allocationId)
                    history.reconciliation.unallocated shouldBeNumerically "50"
                    ledger.paymentHistoriesForLineage(transaction, document.id).single() shouldBe history

                    // Not yet visible to anyone else.
                    onOtherThread {
                        shouldThrow<CommerceFailure.NotFound> { ledger.paymentHistory(paymentId) }
                        ledger.paymentHistoriesForLineage(document.id).shouldBeEmpty()
                    }
                }

                ledger.paymentHistory(paymentId).allocations.map { it.id } shouldContainExactly listOf(allocationId)
                ledger.paymentHistoriesForLineage(document.id).size shouldBe 1
            }
        }

        context("a closed, immutable history") {
            /** A payment with several allocations, refunds, and refund allocations. */
            fun busyHistory(): PaymentHistory {
                val document = invoice("10000.00")
                val payment = payment("1000.00")
                val allocations =
                    listOf("10.00", "20.00", "30.00").mapIndexed { i, amount -> allocate(payment, document, amount, at(i.toLong())) }
                allocations.forEachIndexed { i, allocation ->
                    refund(payment, "2.00", at(10L + i), allocation.id to "1.00")
                }
                return ledger.paymentHistory(payment.id)
            }

            test("the fact lists cannot be mutated, even cast to a mutable list") {
                val history = busyHistory()
                history.allocations.size shouldBe 3
                history.refunds.size shouldBe 3
                history.refundAllocations.size shouldBe 3
                val allocation = history.allocations.first()
                val refundRecord = history.refunds.first()
                val refundAllocation = history.refundAllocations.first()
                val before = history.copyOfFacts()

                @Suppress("UNCHECKED_CAST")
                val allocations = history.allocations as MutableList<PaymentAllocation>

                @Suppress("UNCHECKED_CAST")
                val refunds = history.refunds as MutableList<RefundRecord>

                @Suppress("UNCHECKED_CAST")
                val refundAllocations = history.refundAllocations as MutableList<RefundAllocation>

                shouldThrow<UnsupportedOperationException> { allocations.clear() }
                shouldThrow<UnsupportedOperationException> { allocations.add(allocation) }
                shouldThrow<UnsupportedOperationException> { allocations.removeAt(0) }
                shouldThrow<UnsupportedOperationException> { allocations[0] = allocation }
                shouldThrow<UnsupportedOperationException> { allocations.reverse() }
                shouldThrow<UnsupportedOperationException> { refunds.clear() }
                shouldThrow<UnsupportedOperationException> { refunds.add(refundRecord) }
                shouldThrow<UnsupportedOperationException> { refundAllocations.clear() }
                shouldThrow<UnsupportedOperationException> { refundAllocations.add(refundAllocation) }
                shouldThrow<UnsupportedOperationException> { refundAllocations.iterator().also { it.next() }.remove() }

                history.copyOfFacts() shouldBe before
                history.reconciliation shouldBe reconcileIndependently(history)
            }

            test("the history keeps its own copy of the facts it was built from") {
                val history = busyHistory()
                val allocations = history.allocations.toMutableList()
                val refunds = history.refunds.toMutableList()
                val refundAllocations = history.refundAllocations.toMutableList()

                val rebuilt = PaymentHistory.from(history.payment, allocations, refunds, refundAllocations)
                allocations.clear()
                refunds.clear()
                refundAllocations.clear()

                rebuilt shouldBe history
                rebuilt.allocations.size shouldBe 3
                rebuilt.reconciliation shouldBe reconcileIndependently(rebuilt)
            }

            test("the reconciliation is exactly the reconciliation of the exposed facts") {
                val history = busyHistory()

                history.reconciliation shouldBe reconcileIndependently(history)
                history.reconciliation shouldBe ledger.reconcilePayment(history.payment.id)
                history.reconciliation.totalRefunded shouldBeNumerically "6"
                history.reconciliation.refundAllocations shouldBeNumerically "3"
                val lineage =
                    history.allocations
                        .first()
                        .financialDocumentReference.id
                ledger.paymentHistoriesForLineage(lineage).single().also {
                    it.reconciliation shouldBe reconcileIndependently(it)
                }
            }

            test("the only way in derives the reconciliation from the facts it is given") {
                val history = busyHistory()

                val fewer = PaymentHistory.from(history.payment, history.allocations.take(1), emptyList(), emptyList())

                fewer.reconciliation shouldBe reconcileIndependently(fewer)
                (fewer.reconciliation == history.reconciliation) shouldBe false
            }

            test("no constructor or copy() is publicly callable") {
                val type = PaymentHistory::class.java

                type.constructors.filter { !it.isSynthetic }.shouldBeEmpty()
                type.declaredConstructors
                    .filter { !it.isSynthetic }
                    .forEach { Modifier.isPrivate(it.modifiers) shouldBe true }
                type.methods.filter { it.name == "copy" && !it.isSynthetic }.shouldBeEmpty()
                val copies = type.declaredMethods.filter { it.name == "copy" && !it.isSynthetic }
                copies.shouldNotBeEmpty()
                copies.forEach { Modifier.isPrivate(it.modifiers) shouldBe true }
            }
        }

        context("read consistency") {
            test("the convenience reads run their queries in one REPEATABLE READ transaction") {
                val document = invoice()
                val payment = payment()
                allocate(payment, document, "100.00")
                val isolations = mutableListOf<String>()
                val probe =
                    object : SqlLogger {
                        override fun logBeforeExecution(context: StatementContext) {
                            context.connection.createStatement().use { statement ->
                                statement.executeQuery("SHOW transaction_isolation").use { rows ->
                                    rows.next()
                                    isolations += rows.getString(1)
                                }
                            }
                        }
                    }
                val probedLedger =
                    FinancialLedger(Transactor(Jdbi.create(dataSource).setSqlLogger(probe)), documents, payments)

                probedLedger.paymentHistory(payment.id)
                isolations.shouldNotBeEmpty()
                isolations.toSet() shouldBe setOf("repeatable read")

                isolations.clear()
                probedLedger.paymentHistoriesForLineage(document.id).size shouldBe 1
                isolations.shouldNotBeEmpty()
                isolations.toSet() shouldBe setOf("repeatable read")
            }

            test("a repeatable read caller transaction keeps one snapshot while others commit") {
                val payment = payment()
                allocate(payment, invoice(), "100.00")

                transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
                    val before = ledger.paymentHistory(transaction, payment.id)
                    onOtherThread { refund(payment, "50.00") }

                    ledger.paymentHistory(transaction, payment.id) shouldBe before
                }

                ledger.paymentHistory(payment.id).refunds.size shouldBe 1
            }

            test("reading never blocks or is blocked by an append that holds the payment row lock") {
                val payment = payment()
                allocate(payment, invoice(), "100.00")

                transactor.inTransaction { writer ->
                    ledger.recordRefund(writer, payment.id, UUID.randomUUID(), money("10.00"), PaymentMethod.CARD, at(0))

                    // The writer holds the payment row lock and has not committed; the read does not wait for it.
                    onOtherThread { ledger.paymentHistory(payment.id) }.refunds.shouldBeEmpty()
                }

                ledger.paymentHistory(payment.id).refunds.size shouldBe 1
            }
        }
    })
