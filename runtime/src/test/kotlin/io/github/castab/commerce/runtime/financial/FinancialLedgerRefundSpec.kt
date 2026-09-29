package io.github.castab.commerce.runtime.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.payment.ExternalRefundReference
import io.github.castab.commerce.payment.PaymentAllocation
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.payment.RefundAllocation
import io.github.castab.commerce.payment.RefundRecord
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.PostgresFinancialDocumentRepository
import io.github.castab.commerce.runtime.persistence.PostgresPaymentRepository
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.persistence.createDataSource
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.testApplicationMigrations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val usd = Currency.getInstance("USD")
private val eur = Currency.getInstance("EUR")

private fun money(
    amount: String,
    currency: Currency = usd,
) = Money(BigDecimal(amount), currency)

/** Compares numerically, because `Money` equality is scale-sensitive and derived sums start at zero. */
private infix fun Money.shouldBeNumerically(expected: String) {
    withClue("$this should be numerically $expected") {
        currency shouldBe usd
        amount.compareTo(BigDecimal(expected)) shouldBe 0
    }
}

/**
 * Refunds and refund allocations in the financial ledger, against a real PostgreSQL: the
 * domain examples, every rejection, atomicity of a refund with its refund allocations, and
 * serialization of refunds and allocations through the payment row lock.
 */
class FinancialLedgerRefundSpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var dataSource: com.zaxxer.hikari.HikariDataSource
        lateinit var transactor: Transactor
        val documents = PostgresFinancialDocumentRepository()
        val payments = PostgresPaymentRepository(documents)
        lateinit var ledger: FinancialLedger

        beforeSpec {
            database = TestDatabase.create()
            dataSource = createDataSource(database.configuration, "financial-ledger-refund-spec")
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

        fun payment(amount: String = "500.00"): PaymentRecord =
            ledger.recordPayment(PaymentRecord(UUID.randomUUID(), money(amount), PaymentMethod.CARD, Instant.EPOCH))

        fun allocate(
            payment: PaymentRecord,
            document: FinancialDocument,
            amount: String,
        ): PaymentAllocation = ledger.allocatePayment(payment.id, UUID.randomUUID(), document.reference, money(amount), Instant.EPOCH)

        fun refund(
            payment: PaymentRecord,
            amount: String,
            vararg unwinds: Pair<PaymentAllocation, String>,
            id: UUID = UUID.randomUUID(),
            externalReference: ExternalRefundReference? = null,
        ): RecordedRefund =
            ledger.recordRefund(
                payment.id,
                id,
                money(amount),
                PaymentMethod.CARD,
                Instant.EPOCH,
                externalReference,
                unwinds.map { (allocation, unwound) ->
                    RefundAllocationPortion(UUID.randomUUID(), allocation.id, money(unwound), Instant.EPOCH)
                },
            )

        fun refunds(payment: PaymentRecord): List<RefundRecord> = transactor.inTransaction { payments.refundsForPayment(it, payment.id) }

        fun refundAllocations(payment: PaymentRecord): List<RefundAllocation> =
            transactor.inTransaction { payments.refundAllocationsForPayment(it, payment.id) }

        fun count(table: String): Int =
            transactor.inTransaction { transaction ->
                transaction.handle
                    .createQuery("SELECT count(*) FROM $table")
                    .mapTo(Int::class.java)
                    .one()
            }

        context("the domain examples") {
            test("a refund from unapplied value needs no refund allocation and leaves documents unchanged") {
                val document = invoice()
                val payment = payment()
                allocate(payment, document, "300.00")
                val refundedAt = Instant.parse("2026-02-03T04:05:06.123456789Z")
                val external = ExternalRefundReference("processor", UUID.randomUUID().toString())

                val recorded =
                    ledger.recordRefund(
                        payment.id,
                        UUID.randomUUID(),
                        money("100.00"),
                        PaymentMethod.CASH,
                        refundedAt,
                        external,
                    )

                recorded.allocations.shouldBeEmpty()
                recorded.refund.paymentReference shouldBe payment.id
                recorded.refund.method shouldBe PaymentMethod.CASH
                transactor.inTransaction { payments.retrieveRefund(it, recorded.refund.id) } shouldBe recorded.refund
                refunds(payment).shouldContainExactly(recorded.refund)
                refundAllocations(payment).shouldBeEmpty()

                val reconciliation = ledger.reconcilePayment(payment.id)
                reconciliation.paymentAmount shouldBeNumerically "500"
                reconciliation.totalRefunded shouldBeNumerically "100"
                reconciliation.netReceived shouldBeNumerically "400"
                reconciliation.grossAllocated shouldBeNumerically "300"
                reconciliation.refundAllocations shouldBeNumerically "0"
                reconciliation.netAllocated shouldBeNumerically "300"
                reconciliation.unallocated shouldBeNumerically "100"
                ledger.reconcileLatest(document.id).netApplied shouldBeNumerically "300"
                ledger.reconcileLatest(document.id).refundAllocations shouldBeNumerically "0"
            }

            test("a refund of allocated value unwinds the allocation, and the refunded money is never reusable") {
                val document = invoice()
                val payment = payment()
                val allocation = allocate(payment, document, "500.00")

                // Without its unwind, the refund would leave the payment over-applied.
                shouldThrow<CommerceFailure.InvariantViolated> { refund(payment, "100.00") }
                refunds(payment).shouldBeEmpty()

                val recorded = refund(payment, "100.00", allocation to "100.00")
                val refundAllocation = recorded.allocations.single()
                refundAllocation.refundReference shouldBe recorded.refund.id
                refundAllocation.paymentAllocationReference shouldBe allocation.id
                transactor.inTransaction { transaction ->
                    payments.retrieveRefundAllocation(transaction, refundAllocation.id) shouldBe refundAllocation
                    payments.refundAllocationsForPayment(transaction, payment.id).shouldContainExactly(refundAllocation)
                    payments.refundAllocationsForLineage(transaction, document.id).shouldContainExactly(refundAllocation)
                }

                val paymentReconciliation = ledger.reconcilePayment(payment.id)
                paymentReconciliation.paymentAmount shouldBeNumerically "500"
                paymentReconciliation.totalRefunded shouldBeNumerically "100"
                paymentReconciliation.netReceived shouldBeNumerically "400"
                paymentReconciliation.grossAllocated shouldBeNumerically "500"
                paymentReconciliation.refundAllocations shouldBeNumerically "100"
                paymentReconciliation.netAllocated shouldBeNumerically "400"
                paymentReconciliation.unallocated shouldBeNumerically "0"
                val documentReconciliation = ledger.reconcileLatest(document.id)
                documentReconciliation.grossAllocated shouldBeNumerically "500"
                documentReconciliation.refundAllocations shouldBeNumerically "100"
                documentReconciliation.netApplied shouldBeNumerically "400"
                documentReconciliation.balance shouldBeNumerically "600"
                ledger.reconcile(document.reference).netApplied shouldBeNumerically "400"

                shouldThrow<CommerceFailure.InvariantViolated> { allocate(payment, invoice(), "100.00") }
                transactor.inTransaction { payments.allocationsForPayment(it, payment.id) }.shouldContainExactly(allocation)
            }

            test("a mixed refund takes the rest from unapplied value") {
                val document = invoice()
                val payment = payment()
                val allocation = allocate(payment, document, "300.00")

                refund(payment, "250.00", allocation to "50.00")

                val reconciliation = ledger.reconcilePayment(payment.id)
                reconciliation.netReceived shouldBeNumerically "250"
                reconciliation.netAllocated shouldBeNumerically "250"
                reconciliation.unallocated shouldBeNumerically "0"
                ledger.reconcileLatest(document.id).netApplied shouldBeNumerically "250"
                shouldThrow<CommerceFailure.InvariantViolated> { allocate(payment, document, "0.01") }
            }

            test("a split refund unwinds several allocations of one payment and the rest comes from unapplied value") {
                val first = invoice()
                val second = invoice()
                val payment = payment()
                val a1 = allocate(payment, first, "200.00")
                val a2 = allocate(payment, second, "200.00")

                val recorded = refund(payment, "200.00", a1 to "75.00", a2 to "50.00")

                recorded.allocations.map { it.paymentAllocationReference } shouldContainExactly listOf(a1.id, a2.id)
                refundAllocations(payment) shouldContainExactlyInAnyOrder recorded.allocations
                val reconciliation = ledger.reconcilePayment(payment.id)
                reconciliation.totalRefunded shouldBeNumerically "200"
                reconciliation.netReceived shouldBeNumerically "300"
                reconciliation.grossAllocated shouldBeNumerically "400"
                reconciliation.refundAllocations shouldBeNumerically "125"
                reconciliation.netAllocated shouldBeNumerically "275"
                reconciliation.unallocated shouldBeNumerically "25"
                ledger.reconcileLatest(first.id).netApplied shouldBeNumerically "125"
                ledger.reconcileLatest(second.id).netApplied shouldBeNumerically "150"
                transactor
                    .inTransaction { payments.refundAllocationsForLineage(it, first.id) }
                    .map { it.paymentAllocationReference } shouldContainExactly listOf(a1.id)
            }

            test("allocation accounts for refunds: only net received money can be allocated") {
                val document = invoice()
                val payment = payment()
                refund(payment, "100.00")

                shouldThrow<CommerceFailure.InvariantViolated> { allocate(payment, document, "500.00") }
                allocate(payment, document, "400.00")

                ledger.reconcilePayment(payment.id).unallocated shouldBeNumerically "0"
                shouldThrow<CommerceFailure.InvariantViolated> { allocate(payment, document, "0.01") }
            }

            test("reconciling an unknown payment is not found") {
                shouldThrow<CommerceFailure.NotFound> { ledger.reconcilePayment(UUID.randomUUID()) }
            }
        }

        context("rejections leave no refund facts") {
            test("an unknown payment") {
                val before = count("commerce.refund_records")
                shouldThrow<CommerceFailure.NotFound> {
                    ledger.recordRefund(UUID.randomUUID(), UUID.randomUUID(), money("1.00"), PaymentMethod.CASH, Instant.EPOCH)
                }
                count("commerce.refund_records") shouldBe before
            }

            test("a zero or negative amount") {
                val payment = payment()
                shouldThrow<CommerceFailure.ValidationFailed> { refund(payment, "0.00") }
                shouldThrow<CommerceFailure.ValidationFailed> { refund(payment, "-1.00") }
                refunds(payment).shouldBeEmpty()
            }

            test("a currency mismatch") {
                val payment = payment()
                shouldThrow<CommerceFailure.ValidationFailed> {
                    ledger.recordRefund(payment.id, UUID.randomUUID(), money("10.00", eur), PaymentMethod.CASH, Instant.EPOCH)
                }
                refunds(payment).shouldBeEmpty()
            }

            test("one refund exceeding the payment") {
                val payment = payment()
                shouldThrow<CommerceFailure.ValidationFailed> { refund(payment, "500.01") }
                refunds(payment).shouldBeEmpty()
            }

            test("cumulative refunds exceeding the payment") {
                val payment = payment()
                val first = refund(payment, "300.00")
                shouldThrow<CommerceFailure.InvariantViolated> { refund(payment, "200.01") }
                refunds(payment).shouldContainExactly(first.refund)
                refund(payment, "200.00")
                ledger.reconcilePayment(payment.id).netReceived shouldBeNumerically "0"
            }

            test("a refund allocation of an unknown payment allocation") {
                val payment = payment()
                shouldThrow<CommerceFailure.NotFound> {
                    ledger.recordRefund(
                        payment.id,
                        UUID.randomUUID(),
                        money("10.00"),
                        PaymentMethod.CASH,
                        Instant.EPOCH,
                        null,
                        listOf(RefundAllocationPortion(UUID.randomUUID(), UUID.randomUUID(), money("10.00"), Instant.EPOCH)),
                    )
                }
                refunds(payment).shouldBeEmpty()
            }

            test("a refund allocation of another payment's allocation") {
                val document = invoice()
                val payment = payment()
                val other = payment()
                val otherAllocation = allocate(other, document, "100.00")
                shouldThrow<CommerceFailure.ValidationFailed> {
                    refund(payment, "10.00", otherAllocation to "10.00")
                }.message shouldContain "same payment"
                refunds(payment).shouldBeEmpty()
                refundAllocations(other).shouldBeEmpty()
            }

            test("refund allocations of one refund exceeding that refund") {
                val payment = payment()
                val allocation = allocate(payment, invoice(), "500.00")
                shouldThrow<CommerceFailure.InvariantViolated> {
                    refund(payment, "100.00", allocation to "60.00", allocation to "60.00")
                }
                refunds(payment).shouldBeEmpty()
                refundAllocations(payment).shouldBeEmpty()
            }

            test("cumulative refund allocations reducing an allocation below zero") {
                val payment = payment()
                val small = allocate(payment, invoice(), "100.00")
                allocate(payment, invoice(), "400.00")
                refund(payment, "80.00", small to "80.00")
                shouldThrow<CommerceFailure.InvariantViolated> {
                    refund(payment, "30.00", small to "30.00")
                }.message shouldContain "reduced below zero"
                refunds(payment).size shouldBe 1
                refundAllocations(payment).size shouldBe 1
            }

            test("a refund whose unwinds are too small leaves the payment over-applied") {
                val payment = payment()
                val allocation = allocate(payment, invoice(), "450.00")
                shouldThrow<CommerceFailure.InvariantViolated> {
                    refund(payment, "100.00", allocation to "40.00")
                }.message shouldContain "over-applied"
                refunds(payment).shouldBeEmpty()
                refund(payment, "100.00", allocation to "50.00")
            }

            test("a duplicate refund id, for the same or another payment") {
                val payment = payment()
                val other = payment()
                val existing = refund(payment, "10.00")
                shouldThrow<CommerceFailure.Conflict> { refund(payment, "20.00", id = existing.refund.id) }
                shouldThrow<CommerceFailure.Conflict> { refund(other, "20.00", id = existing.refund.id) }
                refunds(payment).shouldContainExactly(existing.refund)
                refunds(other).shouldBeEmpty()
            }

            test("a duplicate external refund reference, for the same or another payment") {
                val payment = payment()
                val other = payment()
                val external = ExternalRefundReference("processor", UUID.randomUUID().toString())
                val existing = refund(payment, "10.00", externalReference = external)
                shouldThrow<CommerceFailure.Conflict> { refund(payment, "20.00", externalReference = external) }
                shouldThrow<CommerceFailure.Conflict> { refund(other, "20.00", externalReference = external) }
                refunds(payment).shouldContainExactly(existing.refund)
                refunds(other).shouldBeEmpty()
                // The same reference string from another provider is a different refund.
                refund(other, "20.00", externalReference = ExternalRefundReference("other-processor", external.reference))
            }

            test("a duplicate refund allocation id, for the same or another payment") {
                val payment = payment()
                val allocation = allocate(payment, invoice(), "500.00")
                val existing = refund(payment, "10.00", allocation to "10.00").allocations.single()
                val other = payment()
                val otherAllocation = allocate(other, invoice(), "500.00")
                listOf(payment to allocation, other to otherAllocation).forEach { (target, unwound) ->
                    shouldThrow<CommerceFailure.Conflict> {
                        ledger.recordRefund(
                            target.id,
                            UUID.randomUUID(),
                            money("10.00"),
                            PaymentMethod.CASH,
                            Instant.EPOCH,
                            null,
                            listOf(RefundAllocationPortion(existing.id, unwound.id, money("10.00"), Instant.EPOCH)),
                        )
                    }
                }
                refunds(payment).size shouldBe 1
                refunds(other).shouldBeEmpty()
            }

            test("the repository rejects a refund allocation of another refund") {
                val payment = payment()
                val allocation = allocate(payment, invoice(), "500.00")
                val refund = RefundRecord.create(UUID.randomUUID(), payment, money("10.00"), PaymentMethod.CASH, Instant.EPOCH)
                val elsewhere = RefundRecord.create(UUID.randomUUID(), payment, money("10.00"), PaymentMethod.CASH, Instant.EPOCH)
                val misattributed = RefundAllocation.create(UUID.randomUUID(), elsewhere, allocation, money("10.00"), Instant.EPOCH)
                shouldThrow<CommerceFailure.InvariantViolated> {
                    transactor.inTransaction { payments.insertRefund(it, refund, listOf(misattributed)) }
                }
                refunds(payment).shouldBeEmpty()
            }

            test("the database defends refund row shape and references") {
                val payment = payment()

                fun insertRefund(
                    method: String,
                    amount: String,
                    paymentId: UUID = payment.id,
                    provider: String? = null,
                ) = transactor.inTransaction { transaction ->
                    transaction.handle
                        .createUpdate(
                            """INSERT INTO commerce.refund_records
                               (refund_id, payment_id, amount, currency, method, refunded_at_seconds, refunded_at_nanos,
                                external_provider, external_reference)
                               VALUES (:id, :paymentId, :amount, 'USD', :method, 0, 0, :provider, NULL)""",
                        ).bind("id", UUID.randomUUID())
                        .bind("paymentId", paymentId)
                        .bind("amount", BigDecimal(amount))
                        .bind("method", method)
                        .bind("provider", provider)
                        .execute()
                }
                shouldThrow<UnableToExecuteStatementException> { insertRefund("UNSUPPORTED", "1.00") }
                shouldThrow<UnableToExecuteStatementException> { insertRefund("CASH", "0.00") }
                shouldThrow<UnableToExecuteStatementException> { insertRefund("CASH", "1.00", paymentId = UUID.randomUUID()) }
                shouldThrow<UnableToExecuteStatementException> { insertRefund("CASH", "1.00", provider = "processor") }
                shouldThrow<UnableToExecuteStatementException> {
                    transactor.inTransaction { transaction ->
                        transaction.handle
                            .createUpdate(
                                """INSERT INTO commerce.refund_allocations
                                   (refund_allocation_id, refund_id, payment_allocation_id, amount, currency,
                                    allocated_at_seconds, allocated_at_nanos)
                                   VALUES (:id, :refundId, :allocationId, 1.00, 'USD', 0, 0)""",
                            ).bind("id", UUID.randomUUID())
                            .bind("refundId", UUID.randomUUID())
                            .bind("allocationId", UUID.randomUUID())
                            .execute()
                    }
                }
                refunds(payment).shouldBeEmpty()
            }
        }

        context("atomicity") {
            test("a failure while appending a later refund allocation leaves neither the refund nor earlier ones") {
                val payment = payment()
                val first = allocate(payment, invoice(), "250.00")
                val second = allocate(payment, invoice(), "250.00")
                val failing = UUID.randomUUID()
                // A test-only trigger fails the second refund allocation's insert after the
                // refund and the first refund allocation were written in the same transaction.
                transactor.inTransaction { transaction ->
                    transaction.handle.execute(
                        """CREATE FUNCTION public.test_fail_refund_allocation() RETURNS trigger LANGUAGE plpgsql
                           AS 'BEGIN RAISE EXCEPTION ''refund allocation insert failed''; END'""",
                    )
                    transaction.handle.execute(
                        """CREATE TRIGGER test_fail_refund_allocation BEFORE INSERT ON commerce.refund_allocations
                           FOR EACH ROW WHEN (NEW.refund_allocation_id = '$failing')
                           EXECUTE FUNCTION public.test_fail_refund_allocation()""",
                    )
                }
                try {
                    val refundId = UUID.randomUUID()
                    val firstPortion = RefundAllocationPortion(UUID.randomUUID(), first.id, money("60.00"), Instant.EPOCH)
                    shouldThrow<UnableToExecuteStatementException> {
                        ledger.recordRefund(
                            payment.id,
                            refundId,
                            money("100.00"),
                            PaymentMethod.CARD,
                            Instant.EPOCH,
                            null,
                            listOf(firstPortion, RefundAllocationPortion(failing, second.id, money("40.00"), Instant.EPOCH)),
                        )
                    }.message shouldContain "refund allocation insert failed"
                    transactor.inTransaction { transaction ->
                        payments.retrieveRefund(transaction, refundId) shouldBe null
                        payments.retrieveRefundAllocation(transaction, firstPortion.id) shouldBe null
                    }
                    refundAllocations(payment).shouldBeEmpty()
                    ledger.reconcilePayment(payment.id).netAllocated shouldBeNumerically "500"
                } finally {
                    transactor.inTransaction { transaction ->
                        transaction.handle.execute("DROP TRIGGER test_fail_refund_allocation ON commerce.refund_allocations")
                        transaction.handle.execute("DROP FUNCTION public.test_fail_refund_allocation()")
                    }
                }
            }

            test("a refund, its refund allocations, and an application row commit or roll back together") {
                val document = invoice()
                val payment = payment()
                val allocation = allocate(payment, document, "500.00")

                fun Transaction.record(
                    rowId: UUID,
                    refundId: UUID,
                ): RecordedRefund =
                    ledger
                        .recordRefund(
                            this,
                            payment.id,
                            refundId,
                            money("100.00"),
                            PaymentMethod.CARD,
                            Instant.EPOCH,
                            null,
                            listOf(RefundAllocationPortion(UUID.randomUUID(), allocation.id, money("100.00"), Instant.EPOCH)),
                        ).also {
                            handle
                                .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, :value)")
                                .bind("id", rowId)
                                .bind("value", "refund receipt")
                                .execute()
                        }

                val rolledBackRow = UUID.randomUUID()
                val rolledBackRefund = UUID.randomUUID()
                shouldThrow<IllegalStateException> {
                    transactor.inTransaction { transaction ->
                        transaction.record(rolledBackRow, rolledBackRefund)
                        ledger.reconcilePayment(transaction, payment.id).netReceived shouldBeNumerically "400"
                        error("abort")
                    }
                }
                refunds(payment).shouldBeEmpty()
                refundAllocations(payment).shouldBeEmpty()
                count("testapp.test_application_records WHERE id = '$rolledBackRow'") shouldBe 0
                ledger.reconcileLatest(document.id).netApplied shouldBeNumerically "500"

                val committedRow = UUID.randomUUID()
                val recorded = transactor.inTransaction { transaction -> transaction.record(committedRow, UUID.randomUUID()) }
                refunds(payment).shouldContainExactly(recorded.refund)
                refundAllocations(payment).shouldContainExactly(recorded.allocations)
                count("testapp.test_application_records WHERE id = '$committedRow'") shouldBe 1
                ledger.reconcileLatest(document.id).netApplied shouldBeNumerically "400"
            }
        }

        context("serialization through the payment row lock") {
            fun backendPid(transaction: Transaction): Int =
                transaction.handle
                    .createQuery("SELECT pg_backend_pid()")
                    .mapTo(Int::class.java)
                    .one()

            // Observes PostgreSQL itself: returns the waiter's current statement once the waiter
            // is blocked on a lock held by the holder's backend.
            fun awaitBlocked(
                waiter: Int,
                holder: Int,
            ): String {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (true) {
                    val blocked =
                        transactor.inTransaction { transaction ->
                            transaction.handle
                                .createQuery(
                                    """SELECT query FROM pg_stat_activity
                                       WHERE pid = :waiter AND wait_event_type = 'Lock' AND :holder = ANY (pg_blocking_pids(pid))""",
                                ).bind("waiter", waiter)
                                .bind("holder", holder)
                                .mapTo(String::class.java)
                                .findOne()
                                .orElse(null)
                        }
                    if (blocked != null) return blocked
                    check(System.nanoTime() < deadline) { "Backend $waiter was never blocked by backend $holder" }
                    Thread.sleep(10)
                }
            }

            /**
             * Runs [holding] in a transaction that keeps its locks until released, then [waiting]
             * in another transaction. Proves the waiter is blocked by the holder's lock on the
             * payment row, releases the holder, and returns the waiter's outcome.
             */
            fun <T> serialize(
                holding: (Transaction) -> Unit,
                waiting: (Transaction) -> T,
            ): Result<T> {
                val executor = Executors.newFixedThreadPool(2)
                val release = CountDownLatch(1)
                try {
                    val holderPid = CompletableFuture<Int>()
                    val holder =
                        executor.submit {
                            try {
                                transactor.inTransaction { transaction ->
                                    holding(transaction)
                                    holderPid.complete(backendPid(transaction))
                                    release.await(60, TimeUnit.SECONDS)
                                }
                            } catch (e: Throwable) {
                                holderPid.completeExceptionally(e)
                                throw e
                            }
                        }
                    val holderBackend = holderPid.get(30, TimeUnit.SECONDS)
                    val waiterPid = CompletableFuture<Int>()
                    val waiter =
                        executor.submit(
                            Callable {
                                runCatching {
                                    transactor.inTransaction { transaction ->
                                        waiterPid.complete(backendPid(transaction))
                                        waiting(transaction)
                                    }
                                }
                            },
                        )
                    val blockedStatement = awaitBlocked(waiterPid.get(30, TimeUnit.SECONDS), holderBackend)
                    blockedStatement shouldContain "commerce.payment_records"
                    blockedStatement shouldContain "FOR UPDATE"
                    waiter.isDone shouldBe false

                    release.countDown()
                    holder.get(30, TimeUnit.SECONDS)
                    return waiter.get(30, TimeUnit.SECONDS)
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                }
            }

            test("an allocation waits for a refund that holds the payment lock, then sees the committed refund") {
                val document = invoice()
                val payment = payment()
                val allocation = allocate(payment, document, "400.00")
                // Before the refund, 100 is unallocated, so this allocation alone would succeed.
                val competing = UUID.randomUUID()

                val outcome =
                    serialize(
                        holding = { transaction ->
                            ledger.recordRefund(
                                transaction,
                                payment.id,
                                UUID.randomUUID(),
                                money("150.00"),
                                PaymentMethod.CARD,
                                Instant.EPOCH,
                                null,
                                listOf(RefundAllocationPortion(UUID.randomUUID(), allocation.id, money("50.00"), Instant.EPOCH)),
                            )
                        },
                        waiting = { transaction ->
                            ledger.allocatePayment(transaction, payment.id, competing, document.reference, money("100.00"), Instant.EPOCH)
                        },
                    )

                outcome.exceptionOrNull().shouldBeInstanceOf<CommerceFailure.InvariantViolated>()
                transactor.inTransaction { payments.retrieveAllocation(it, competing) } shouldBe null
                val reconciliation = ledger.reconcilePayment(payment.id)
                reconciliation.netReceived shouldBeNumerically "350"
                reconciliation.netAllocated shouldBeNumerically "350"
                reconciliation.unallocated shouldBeNumerically "0"
            }

            test("a refund waits for an allocation that holds the payment lock, then sees the committed allocation") {
                val document = invoice()
                val payment = payment()
                // Before the allocation, the whole payment is unallocated, so this refund alone would succeed.
                val competing = UUID.randomUUID()

                val outcome =
                    serialize(
                        holding = { transaction ->
                            ledger.allocatePayment(
                                transaction,
                                payment.id,
                                UUID.randomUUID(),
                                document.reference,
                                money("400.00"),
                                Instant.EPOCH,
                            )
                        },
                        waiting = { transaction ->
                            ledger.recordRefund(transaction, payment.id, competing, money("200.00"), PaymentMethod.CARD, Instant.EPOCH)
                        },
                    )

                outcome.exceptionOrNull().shouldBeInstanceOf<CommerceFailure.InvariantViolated>()
                transactor.inTransaction { payments.retrieveRefund(it, competing) } shouldBe null
                val reconciliation = ledger.reconcilePayment(payment.id)
                reconciliation.netReceived shouldBeNumerically "500"
                reconciliation.netAllocated shouldBeNumerically "400"
                reconciliation.unallocated shouldBeNumerically "100"
            }
        }
    })
