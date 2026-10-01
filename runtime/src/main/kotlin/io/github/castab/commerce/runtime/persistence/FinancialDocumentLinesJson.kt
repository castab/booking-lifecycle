package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * The stored form of one financial document line, an element of the `lines` array of
 * `commerce.financial_document_snapshots`. Array order is line order.
 *
 * ```
 * {"id": "<uuid>", "description": "...", "subDescription": null, "quantity": "2.500",
 *  "priceAmount": "12.3400", "taxAmount": "0.00", "currency": "USD"}
 * ```
 *
 * Amounts and quantity are plain decimal strings, never JSON numbers, so scale survives every
 * JSON reader. A line has one `currency`, which is the currency of both its price and tax.
 * Every property is required; `subDescription` and `quantity` are explicitly `null` when absent.
 */
@Serializable
internal class FinancialDocumentLineJson(
    val id: String,
    val description: String,
    val subDescription: String?,
    val quantity: String?,
    val priceAmount: String,
    val taxAmount: String,
    val currency: String,
)

private val linesSerializer = ListSerializer(FinancialDocumentLineJson.serializer())

internal fun List<LineItem>.toStoredLines(): String =
    encodeStored(
        linesSerializer,
        map { line ->
            FinancialDocumentLineJson(
                id = line.id.toString(),
                description = line.description,
                subDescription = line.subDescription,
                quantity = line.quantity?.toStoredDecimal(),
                priceAmount = line.price.amount.toStoredDecimal(),
                taxAmount = line.taxAmount.amount.toStoredDecimal(),
                currency = line.currency.toStoredCurrency(),
            )
        },
    )

internal fun restoreStoredLines(
    what: String,
    json: String,
): List<LineItem> =
    restoreStored(what, linesSerializer, json) { lines ->
        lines.map { line ->
            val currency = line.currency.toCurrency()
            LineItem(
                id = line.id.toStoredUuid(),
                description = line.description,
                subDescription = line.subDescription,
                quantity = line.quantity?.toDecimal(),
                price = Money(line.priceAmount.toDecimal(), currency),
                taxAmount = Money(line.taxAmount.toDecimal(), currency),
            )
        }
    }
