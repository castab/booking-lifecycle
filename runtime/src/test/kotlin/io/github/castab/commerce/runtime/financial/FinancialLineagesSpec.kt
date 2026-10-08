package io.github.castab.commerce.runtime.financial

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.payment.FinancialDocumentReconciliation
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.testing.FinancialLedgerFixture
import io.github.castab.commerce.runtime.testing.depositMoney
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.SqlLogger
import org.jdbi.v3.core.statement.StatementContext
import java.time.Instant
import java.util.UUID

class FinancialLineagesSpec :
    FunSpec({
        lateinit var fixture: FinancialLedgerFixture
        beforeSpec { fixture = FinancialLedgerFixture() }
        afterSpec { fixture.close() }

        test("empty input returns empty and duplicate or missing ids fail rather than omitting results") {
            val ledger = fixture.ledger
            ledger.financialLineages(emptyList()).shouldBeEmpty()
            val document = fixture.document()
            shouldThrow<CommerceFailure.ValidationFailed> { ledger.financialLineages(listOf(document.id, document.id)) }
            shouldThrow<CommerceFailure.NotFound> { ledger.financialLineages(listOf(document.id, UUID.randomUUID())) }
        }

        test("multiple lineages preserve requested order exact reconciliation and all requirement distinctions") {
            val ledger = fixture.ledger
            val never = fixture.document()
            val active = fixture.document()
            val withdrawn = fixture.document()
            val approval = ledger.activateDepositRequirement(active.id, active.version, DepositTerms.Fixed(depositMoney("25")), null)
            ledger.activateDepositRequirement(withdrawn.id, withdrawn.version, DepositTerms.Fixed(depositMoney("10")), null)
            val withdrawal = ledger.withdrawDepositRequirement(withdrawn.id, DepositRequirementRevision.INITIAL)
            val latest = ledger.issueInvoice(active.id, expectedDocumentVersion = active.version)
            val payment = fixture.payment()
            val allocatedAt = Instant.parse("2040-01-01T00:00:00.123456789Z")
            val allocation = ledger.allocatePayment(payment.id, UUID.randomUUID(), active.reference, depositMoney("30"), allocatedAt)
            val otherAllocation =
                ledger.allocatePayment(
                    payment.id,
                    UUID.randomUUID(),
                    never.reference,
                    depositMoney("5"),
                    allocatedAt.plusSeconds(1),
                )
            val unwoundAt = allocatedAt.plusSeconds(10)
            val refund =
                ledger.recordRefund(
                    payment.id,
                    UUID.randomUUID(),
                    depositMoney("5"),
                    PaymentMethod.CASH,
                    Instant.EPOCH,
                    null,
                    listOf(RefundAllocationPortion(UUID.randomUUID(), allocation.id, depositMoney("5"), unwoundAt)),
                )
            val ids = listOf(withdrawn.id, active.id, never.id)
            val views = ledger.financialLineages(ids)
            views.map { it.latestVersion.document.id }.shouldContainExactly(ids)
            views[0].depositRequirement!!.requirement.shouldBeInstanceOf<DepositRequirement.Withdrawn>()
            views[0].depositSatisfied shouldBe null
            views[0].activity.latestDepositRequirementAt shouldBe withdrawal.createdAt
            views[1].latestVersion.document shouldBe latest
            views[1].reconciliation shouldBe
                FinancialDocumentReconciliation.reconcile(latest, listOf(allocation), emptyList(), refund.allocations)
            views[1].depositRequirement!!.requirement shouldBe approval.requirement
            views[1].depositSatisfied shouldBe true
            views[1].activity.latestDocumentVersionAt shouldBe ledger.latestVersion(active.id).createdAt
            views[1].activity.latestDepositRequirementAt shouldBe approval.createdAt
            views[1].activity.latestPaymentAllocationAt shouldBe allocatedAt
            views[1].activity.latestRefundAllocationAt shouldBe unwoundAt
            views[1].activity.latestFinancialActivityAt shouldBe unwoundAt
            views[2].depositRequirement shouldBe null
            views[2].depositSatisfied shouldBe null
            views[2].reconciliation shouldBe FinancialDocumentReconciliation.reconcile(never, listOf(otherAllocation))
            views[2].activity.latestRefundAllocationAt shouldBe null
            shouldThrow<UnsupportedOperationException> { (views as MutableList).clear() }
        }

        test("unapplied payment receipt and standalone refund times never affect lineage activity") {
            val document = fixture.document()
            val ledger = fixture.ledger
            val baseline =
                ledger
                    .financialLineages(listOf(document.id))
                    .single()
                    .activity.latestFinancialActivityAt
            val payment = fixture.payment(receivedAt = Instant.parse("2100-01-01T00:00:00Z"))
            ledger.recordRefund(
                payment.id,
                UUID.randomUUID(),
                depositMoney("10"),
                PaymentMethod.CASH,
                Instant.parse("2200-01-01T00:00:00Z"),
            )
            val untouched = ledger.financialLineages(listOf(document.id)).single()
            untouched.activity.latestFinancialActivityAt shouldBe baseline
            untouched.activity.latestPaymentAllocationAt shouldBe null
            untouched.activity.latestRefundAllocationAt shouldBe null
            val allocationAt = Instant.parse("2040-01-01T00:00:00Z")
            ledger.allocatePayment(payment.id, UUID.randomUUID(), document.reference, depositMoney("10"), allocationAt)
            val touched = ledger.financialLineages(listOf(document.id)).single()
            touched.activity.latestPaymentAllocationAt shouldBe allocationAt
            touched.activity.latestFinancialActivityAt shouldBe allocationAt
        }

        test("fully refunded allocations retain historical allocation and unwind activity using the unwind time") {
            val document = fixture.document()
            val ledger = fixture.ledger
            val payment = fixture.payment(receivedAt = Instant.parse("2200-01-01T00:00:00Z"))
            val at = Instant.parse("2040-01-01T00:00:00Z")
            val allocation = ledger.allocatePayment(payment.id, UUID.randomUUID(), document.reference, depositMoney("25"), at)
            ledger.recordRefund(
                payment.id,
                UUID.randomUUID(),
                depositMoney("25"),
                PaymentMethod.CASH,
                Instant.parse("2300-01-01T00:00:00Z"),
                null,
                listOf(RefundAllocationPortion(UUID.randomUUID(), allocation.id, depositMoney("25"), at.plusSeconds(1))),
            )
            val view = ledger.financialLineages(listOf(document.id)).single()
            view.reconciliation.netApplied.amount
                .compareTo("0".toBigDecimal()) shouldBe 0
            view.activity.latestPaymentAllocationAt shouldBe at
            view.activity.latestRefundAllocationAt shouldBe at.plusSeconds(1)
            view.activity.latestFinancialActivityAt shouldBe at.plusSeconds(1)
        }

        test("document and requirement activity are independent constituents of their exact maximum") {
            val document = fixture.document()
            val ledger = fixture.ledger
            val first = ledger.financialLineages(listOf(document.id)).single()
            first.activity.latestFinancialActivityAt shouldBe first.latestVersion.createdAt
            val approved = ledger.activateDepositRequirement(document.id, document.version, DepositTerms.Fixed(depositMoney("25")), null)
            val second = ledger.financialLineages(listOf(document.id)).single()
            second.activity.latestDepositRequirementAt shouldBe approved.createdAt
            second.activity.latestFinancialActivityAt shouldBe maxOf(first.latestVersion.createdAt, approved.createdAt)
            ledger.issueInvoice(document.id, expectedDocumentVersion = document.version)
            val third = ledger.financialLineages(listOf(document.id)).single()
            third.activity.latestFinancialActivityAt shouldBe maxOf(third.latestVersion.createdAt, approved.createdAt)
        }

        test("bulk query count stays four for many requested lineages and reads all facts at REPEATABLE_READ") {
            val ids = (1..20).map { fixture.document().id }
            val sql = mutableListOf<String>()
            val isolations = mutableListOf<String>()
            val probe =
                object : SqlLogger {
                    override fun logBeforeExecution(context: StatementContext) {
                        sql += context.rawSql
                        context.connection.createStatement().use { statement ->
                            statement.executeQuery("SHOW transaction_isolation").use { rows ->
                                rows.next()
                                isolations += rows.getString(1)
                            }
                        }
                    }
                }
            val ledger =
                FinancialLedger(Transactor(Jdbi.create(fixture.dataSource).setSqlLogger(probe)), fixture.documents, fixture.payments)
            ledger.financialLineages(ids).size shouldBe ids.size
            sql.size shouldBe 4
            sql.none { "FOR UPDATE" in it || "FOR NO KEY UPDATE" in it } shouldBe true
            isolations.toSet() shouldBe setOf("repeatable read")
            sql.clear()
            ledger.financialLineages(emptyList()).shouldBeEmpty()
            sql.shouldBeEmpty()
        }

        test("convenience bulk read retains one snapshot when all constituent facts change between queries") {
            val document = fixture.document()
            val ledger = fixture.ledger
            val payment = fixture.payment()
            ledger.activateDepositRequirement(document.id, document.version, DepositTerms.Fixed(depositMoney("25")), null)
            val allocation = ledger.allocatePayment(payment.id, UUID.randomUUID(), document.reference, depositMoney("25"), Instant.EPOCH)
            val before = ledger.financialLineages(listOf(document.id)).single()
            var committed = false
            val probe =
                object : SqlLogger {
                    override fun logAfterExecution(context: StatementContext) {
                        if (!committed && "SELECT DISTINCT ON (document_id)" in context.rawSql) {
                            committed = true
                            fixture.onOtherThread {
                                fixture.transactor.inTransaction { transaction ->
                                    val latest =
                                        ledger.issueInvoice(
                                            transaction,
                                            document.id,
                                            expectedDocumentVersion = document.version,
                                        )
                                    ledger.activateDepositRequirement(
                                        transaction,
                                        document.id,
                                        latest.version,
                                        DepositTerms.Fixed(depositMoney("60")),
                                        DepositRequirementRevision.INITIAL,
                                    )
                                    ledger.allocatePayment(
                                        transaction,
                                        payment.id,
                                        UUID.randomUUID(),
                                        latest.reference,
                                        depositMoney("25"),
                                        Instant.EPOCH.plusSeconds(10),
                                    )
                                    ledger.recordRefund(
                                        transaction,
                                        payment.id,
                                        UUID.randomUUID(),
                                        depositMoney("5"),
                                        PaymentMethod.CASH,
                                        Instant.EPOCH,
                                        null,
                                        listOf(
                                            RefundAllocationPortion(
                                                UUID.randomUUID(),
                                                allocation.id,
                                                depositMoney("5"),
                                                Instant.EPOCH.plusSeconds(20),
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            val probed =
                FinancialLedger(Transactor(Jdbi.create(fixture.dataSource).setSqlLogger(probe)), fixture.documents, fixture.payments)
            val coherent = probed.financialLineages(listOf(document.id)).single()
            committed shouldBe true
            coherent.latestVersion.document shouldBe before.latestVersion.document
            coherent.reconciliation shouldBe before.reconciliation
            coherent.depositRequirement!!.requirement shouldBe before.depositRequirement!!.requirement
            coherent.depositSatisfied shouldBe true
            coherent.activity.latestFinancialActivityAt shouldBe before.activity.latestFinancialActivityAt
            val after = ledger.financialLineages(listOf(document.id)).single()
            after.latestVersion.document.version.number shouldBe 2
            after.reconciliation.netApplied.amount
                .compareTo("45".toBigDecimal()) shouldBe 0
            after.depositRequirement!!
                .requirement.revision.number shouldBe 2
            after.depositSatisfied shouldBe false
            after.activity.latestPaymentAllocationAt shouldBe Instant.EPOCH.plusSeconds(10)
            after.activity.latestRefundAllocationAt shouldBe Instant.EPOCH.plusSeconds(20)
        }

        test("caller-transaction bulk reads retain caller isolation and visibility without nesting") {
            val document = fixture.document()
            fixture.transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
                val before = fixture.ledger.financialLineages(transaction, listOf(document.id)).single()
                fixture.onOtherThread {
                    fixture.ledger.issueInvoice(
                        document.id,
                        expectedDocumentVersion = document.version,
                    )
                }
                fixture.ledger
                    .financialLineages(transaction, listOf(document.id))
                    .single()
                    .latestVersion.document shouldBe
                    before.latestVersion.document
            }
            fixture.transactor.inTransaction { transaction ->
                val latest = fixture.ledger.latest(transaction, document.id)
                fixture.ledger.activateDepositRequirement(
                    transaction,
                    document.id,
                    latest.version,
                    DepositTerms.Fixed(depositMoney("25")),
                    null,
                )
                fixture.ledger
                    .financialLineages(
                        transaction,
                        listOf(document.id),
                    ).single()
                    .depositRequirement!!
                    .requirement.revision.number shouldBe
                    1
                transaction.handle
                    .createQuery("SHOW transaction_isolation")
                    .mapTo(String::class.java)
                    .one() shouldBe "read committed"
            }
        }
    })
