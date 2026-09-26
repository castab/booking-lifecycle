package io.github.castab.commerce.payment.fixtures

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.fixtures.lineItem
import io.github.castab.commerce.payment.ExternalPaymentReference
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentRecord
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/*
 * Test-only helpers for the payment domain. Payments, allocations, refunds, money, and
 * documents are real immutable values; they are never mocked.
 */

val RECEIVED_AT: Instant = Instant.parse("2026-03-02T15:30:00Z")

/** A moment [minutes] after [RECEIVED_AT], so that records read in a plausible order. */
fun minutesLater(minutes: Long): Instant = RECEIVED_AT.plusSeconds(minutes * 60)

fun payment(
    amount: Money,
    method: PaymentMethod = PaymentMethod.CARD,
    id: UUID = UUID.randomUUID(),
    externalReference: ExternalPaymentReference? = null,
): PaymentRecord = PaymentRecord(id, amount, method, RECEIVED_AT, externalReference)

/** One flat-priced, untaxed line, so the document total is exactly [total]. */
fun flatLine(
    total: Money,
    description: String = "Services",
    id: UUID = UUID.randomUUID(),
): LineItem = lineItem(description, quantity = null, price = total, taxAmount = Money.zero(total.currency), id = id)

fun estimateTotalling(
    total: Money,
    id: UUID = UUID.randomUUID(),
): FinancialDocument.Estimate = FinancialDocument.Estimate.create(id, listOf(flatLine(total)))

fun quoteTotalling(
    total: Money,
    id: UUID = UUID.randomUUID(),
): FinancialDocument.Quote = FinancialDocument.Quote.create(id, listOf(flatLine(total)))

fun invoiceTotalling(
    total: Money,
    id: UUID = UUID.randomUUID(),
): FinancialDocument.Invoice = FinancialDocument.Invoice.create(id, listOf(flatLine(total)))

/** A change order that replaces every line of [document] with one flat line of [total]. */
fun retotal(
    document: FinancialDocument,
    total: Money,
): ChangeOrder =
    ChangeOrder(
        document.lineItems.map { ChangeOrder.Change.RemoveLineItem(it.id) } +
            ChangeOrder.Change.AddLineItem(flatLine(total)),
    )

/**
 * Asserts numeric equality in the same currency. [Money] equality is scale-sensitive, and
 * derived sums may carry a different scale from the literal they are compared with.
 */
infix fun Money.shouldBeNumerically(expected: Money) {
    withClue("$this should numerically equal $expected") {
        currency shouldBe expected.currency
        amount.compareTo(expected.amount) shouldBe 0
    }
}
