package io.github.castab.commerce.financial

import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * One immutable commercial line on a [FinancialDocument].
 *
 * The [id] is supplied by the application and identifies the line within its document, so
 * that a [ChangeOrder] can replace or remove it. It must be unique within one document.
 *
 * The line is self-contained. Products, catalog provenance, selection rules, and pricing
 * authority belong to the application; no shared catalog reference is required.
 *
 * ## Quantity semantics
 *
 * - When [quantity] is present, [price] is a unit price and the line [subtotal] is
 *   `price × quantity` (for example 4 × 25.00 = 100.00).
 * - When [quantity] is `null`, the line is flat-priced: a service fee, labor, or another
 *   charge that is not counted in units. The line [subtotal] is [price] itself.
 *
 * ## Tax
 *
 * [price] never includes tax. [taxAmount] is the final tax attributable to the whole line,
 * already calculated by the application. It is not a rate, and it is not the taxable
 * amount. The line [total] is `subtotal + taxAmount`.
 *
 * [price] and [taxAmount] must be in the same currency, which is the line's [currency].
 *
 * The library does not constrain signs: negative prices, quantities, or tax amounts can
 * express discounts, returns, or credits when an application needs them.
 *
 * Being a data class, a line item can be revised with `copy(...)`. The copy is validated
 * like any other line item. Use [ChangeOrder.Change.ReplaceLineItem] to put the revision
 * on a document.
 *
 * @property description Short description of what is being charged for. Must not be blank.
 * @property subDescription Optional secondary text, such as details or notes.
 */
data class LineItem(
    val id: UUID,
    val description: String,
    val subDescription: String? = null,
    val quantity: BigDecimal?,
    val price: Money,
    val taxAmount: Money,
) {
    init {
        require(description.isNotBlank()) { "Line item $id must have a non-blank description" }
        require(price.currency == taxAmount.currency) {
            "Line item $id has price in ${price.currency.currencyCode} but tax in " +
                "${taxAmount.currency.currencyCode}: currencies must match"
        }
    }

    /** The currency of this line: the currency of both [price] and [taxAmount]. */
    val currency: Currency
        get() = price.currency

    /** The line amount before tax: `price × quantity`, or [price] when [quantity] is `null`. */
    val subtotal: Money = if (quantity == null) price else price * quantity

    /** The line amount including tax: `subtotal + taxAmount`. */
    val total: Money = subtotal + taxAmount
}
