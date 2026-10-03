package io.github.castab.commerce.deposit

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.payment.FinancialDocumentReconciliation
import io.github.castab.commerce.payment.PaymentAllocation
import io.github.castab.commerce.payment.PaymentAllocationReversal
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.payment.RefundAllocation
import io.github.castab.commerce.payment.RefundRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID

private fun money(
    value: String,
    currency: String = "USD",
) = Money(BigDecimal(value), Currency.getInstance(currency))

private fun document(
    value: String = "100.00",
    currency: String = "USD",
    id: UUID = UUID.randomUUID(),
) = FinancialDocument.Quote.create(
    id,
    listOf(LineItem(UUID.randomUUID(), "Service", null, null, money(value, currency), money("0", currency))),
)

class DepositRequirementSpec :
    FunSpec({
        test("fixed approval retains exact terms and resolved money") {
            val snapshot = document()
            val terms = DepositTerms.Fixed(money("25.000"))
            val requirement = DepositRequirement.Active.create(snapshot, terms)
            requirement.requiredAmount shouldBe money("25.000")
            requirement.terms shouldBe terms
            requirement.approvalReference shouldBe snapshot.reference
            requirement.documentId shouldBe snapshot.id
            requirement.revision shouldBe DepositRequirementRevision.INITIAL
            requirement.previousRevision shouldBe null
        }

        listOf("0", "-1", "0.00").forEach { value ->
            test("fixed terms reject $value") { shouldThrow<IllegalArgumentException> { DepositTerms.Fixed(money(value)) } }
        }
        listOf("0", "-0.01", "100.01").forEach { value ->
            test("percentage terms reject $value") { shouldThrow<IllegalArgumentException> { DepositTerms.Percentage(BigDecimal(value)) } }
        }
        listOf("0.01", "25.000", "100").forEach { value ->
            test(
                "percentage terms retain exact $value",
            ) { DepositTerms.Percentage(BigDecimal(value)).percentage shouldBe BigDecimal(value) }
        }
        listOf(
            listOf("100.00", "25", "USD", "25.00"),
            listOf("0.10", "25", "USD", "0.03"),
            listOf("101", "50", "JPY", "51"),
            listOf("1.005", "50", "KWD", "0.503"),
            listOf("100.12", "100", "USD", "100.12"),
        ).forEach { (total, percentage, currency, expected) ->
            test("$percentage percent of $total $currency resolves to $expected") {
                DepositTerms.Percentage(BigDecimal(percentage)).resolve(document(total, currency)) shouldBe money(expected, currency)
            }
        }
        test("rounding to zero is rejected") {
            shouldThrow<IllegalArgumentException> { DepositTerms.Percentage(BigDecimal("1")).resolve(document("0.01")) }
        }
        test("rounding above the exact approval total is rejected rather than capped") {
            shouldThrow<IllegalArgumentException> { DepositTerms.Percentage(BigDecimal("100")).resolve(document("0.005")) }
        }
        test("a fixed amount cannot exceed the total or use another currency") {
            shouldThrow<IllegalArgumentException> { DepositTerms.Fixed(money("100.01")).resolve(document()) }
            shouldThrow<IllegalArgumentException> { DepositTerms.Fixed(money("25", "EUR")).resolve(document()) }
            shouldThrow<IllegalArgumentException> { DepositTerms.Fixed(money("1")).resolve(document("0")) }
        }
        test("percentages require minor-unit metadata") {
            shouldThrow<IllegalArgumentException> { DepositTerms.Percentage(BigDecimal("25")).resolve(document("100", "XXX")) }
        }
        test("revision stream replaces withdraws and reactivates without embedding history") {
            val snapshot = document()
            val first = DepositRequirement.Active.create(snapshot, DepositTerms.Fixed(money("25")))
            val second = first.activate(snapshot.toInvoice(), DepositTerms.Percentage(BigDecimal("50")))
            val third = second.withdraw()
            val fourth = third.activate(snapshot, DepositTerms.Fixed(money("10")))
            listOf(first, second, third, fourth).map { it.revision.number } shouldBe listOf(1, 2, 3, 4)
            third.previousRevision shouldBe second.revision
            first.requiredAmount shouldBe money("25")
            shouldThrow<IllegalArgumentException> { first.activate(document(), DepositTerms.Fixed(money("1"))) }
            shouldThrow<IllegalArgumentException> { DepositRequirement.Withdrawn.restore(snapshot.id, DepositRequirementRevision.INITIAL) }
        }
        test("revisions reject zero and negative values and detect overflow") {
            listOf(0, -1).forEach { shouldThrow<IllegalArgumentException> { DepositRequirementRevision.of(it) } }
            shouldThrow<ArithmeticException> { DepositRequirementRevision.of(Int.MAX_VALUE).next() }
        }
        listOf("24.99" to false, "25.00" to true, "25.01" to true).forEach { (applied, satisfied) ->
            test("satisfaction at $applied is $satisfied") {
                val snapshot = document()
                val payment = PaymentRecord(UUID.randomUUID(), money("100"), PaymentMethod.CASH, Instant.EPOCH)
                val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, snapshot, money(applied), Instant.EPOCH)
                val requirement = DepositRequirement.Active.create(snapshot, DepositTerms.Fixed(money("25")))
                requirement.isSatisfiedBy(FinancialDocumentReconciliation.reconcile(snapshot.toInvoice(), listOf(allocation))) shouldBe
                    satisfied
            }
        }
        test("refunds and corrections lower netApplied and may undo satisfaction") {
            val snapshot = document()
            val payment = PaymentRecord(UUID.randomUUID(), money("100"), PaymentMethod.CASH, Instant.EPOCH)
            val allocation = PaymentAllocation.create(UUID.randomUUID(), payment, snapshot, money("25"), Instant.EPOCH)
            val refund = RefundRecord.create(UUID.randomUUID(), payment, money("1"), PaymentMethod.CASH, Instant.EPOCH)
            val unwind = RefundAllocation.create(UUID.randomUUID(), refund, allocation, money("1"), Instant.EPOCH)
            val reversal = PaymentAllocationReversal.create(UUID.randomUUID(), allocation, money("1"), Instant.EPOCH)
            val requirement = DepositRequirement.Active.create(snapshot, DepositTerms.Fixed(money("25")))
            requirement.isSatisfiedBy(FinancialDocumentReconciliation.reconcile(snapshot, listOf(allocation))) shouldBe true
            requirement.isSatisfiedBy(
                FinancialDocumentReconciliation.reconcile(snapshot, listOf(allocation), emptyList(), listOf(unwind)),
            ) shouldBe
                false
            requirement.isSatisfiedBy(FinancialDocumentReconciliation.reconcile(snapshot, listOf(allocation), listOf(reversal))) shouldBe
                false
        }
        test("satisfaction rejects another lineage or currency") {
            val snapshot = document()
            val requirement = DepositRequirement.Active.create(snapshot, DepositTerms.Fixed(money("25")))
            shouldThrow<IllegalArgumentException> {
                requirement.isSatisfiedBy(
                    FinancialDocumentReconciliation.reconcile(document(), emptyList()),
                )
            }
            shouldThrow<IllegalArgumentException> {
                requirement.isSatisfiedBy(
                    FinancialDocumentReconciliation.reconcile(document(currency = "EUR", id = snapshot.id), emptyList()),
                )
            }
        }
        test("restoration validates frozen amount against exact original terms and total") {
            val snapshot = document()
            val terms = DepositTerms.Percentage(BigDecimal("25"))
            val first = DepositRequirement.Active.create(snapshot, terms)
            DepositRequirement.Active.restore(snapshot, first.revision, terms, money("25.00")) shouldBe first
            shouldThrow<IllegalArgumentException> { DepositRequirement.Active.restore(snapshot, first.revision, terms, money("26.00")) }
            shouldThrow<IllegalArgumentException> { DepositRequirement.Active.restore(snapshot, first.revision, terms, money("25.0")) }
        }
        test("requirement facts have closed construction no copy and no embedded document or predecessor") {
            listOf(DepositRequirement.Active::class.java, DepositRequirement.Withdrawn::class.java).forEach { type ->
                type.declaredConstructors.filterNot { it.isSynthetic }.all { Modifier.isPrivate(it.modifiers) } shouldBe true
                type.methods.none { it.name == "copy" } shouldBe true
                type.declaredFields.none {
                    FinancialDocument::class.java.isAssignableFrom(it.type) ||
                        DepositRequirement::class.java.isAssignableFrom(it.type)
                } shouldBe
                    true
                type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.all { Modifier.isFinal(it.modifiers) } shouldBe true
            }
        }
    })
