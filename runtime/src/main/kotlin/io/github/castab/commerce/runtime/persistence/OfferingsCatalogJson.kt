package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingAvailability
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingPrice
import io.github.castab.commerce.offering.OfferingSelectionState
import io.github.castab.commerce.offering.QuantityDimension
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration

/**
 * The stored contents of one offerings catalog revision, the `catalog` column of
 * `commerce.offerings_snapshots`. Array order is category and offering order.
 *
 * ```
 * {"categories": [{"key": "...", "displayName": "...", "description": null,
 *                  "minimumSelections": 0, "maximumSelections": null}],
 *  "offerings":  [{"key": "...", "category": "...", "displayName": "...", "description": null,
 *                  "price": null | {"kind": "FIXED", "amount": "120.00", "currency": "USD"}
 *                                | {"kind": "PER_QUANTITY", "amount": "0.7500", "currency": "EUR", "dimension": "item"}
 *                                | {"kind": "PER_DURATION", "amount": "50.125", "currency": "USD",
 *                                   "seconds": 3600, "nanos": 123456789},
 *                  "selectionState": "ENABLED" | "DISABLED",
 *                  "availability": "AVAILABLE" | "UNAVAILABLE"}]}
 * ```
 *
 * Every property is required, with `null` written explicitly where a value is absent. Price
 * kinds and the enums are explicit names, never class names or ordinals, and money is a
 * plain decimal string with its currency code. The catalog id, revision, and predecessor are
 * columns of the row and are not repeated here.
 */
@Serializable
internal class OfferingsCatalogJson(
    val categories: List<OfferingCategoryJson>,
    val offerings: List<OfferingJson>,
)

@Serializable
internal class OfferingCategoryJson(
    val key: String,
    val displayName: String,
    val description: String?,
    @Serializable(with = StrictIntSerializer::class) val minimumSelections: Int,
    @Serializable(with = StrictIntSerializer::class) val maximumSelections: Int?,
)

@Serializable
internal class OfferingJson(
    val key: String,
    val category: String,
    val displayName: String,
    val description: String?,
    val price: OfferingPriceJson?,
    val selectionState: String,
    val availability: String,
)

@Serializable
internal sealed class OfferingPriceJson {
    @Serializable
    @SerialName("FIXED")
    class Fixed(
        val amount: String,
        val currency: String,
    ) : OfferingPriceJson()

    @Serializable
    @SerialName("PER_QUANTITY")
    class PerQuantity(
        val amount: String,
        val currency: String,
        val dimension: String,
    ) : OfferingPriceJson()

    @Serializable
    @SerialName("PER_DURATION")
    class PerDuration(
        val amount: String,
        val currency: String,
        @Serializable(with = StrictLongSerializer::class) val seconds: Long,
        @Serializable(with = StrictIntSerializer::class) val nanos: Int,
    ) : OfferingPriceJson()
}

internal fun OfferingCategory.toStored(): OfferingCategoryJson =
    OfferingCategoryJson(key.value, displayName, description, minimumSelections, maximumSelections)

internal fun Offering.toStored(): OfferingJson =
    OfferingJson(
        key = key.value,
        category = category.value,
        displayName = displayName,
        description = description,
        price = price?.toStored(),
        selectionState =
            when (selectionState) {
                OfferingSelectionState.ENABLED -> "ENABLED"
                OfferingSelectionState.DISABLED -> "DISABLED"
            },
        availability =
            when (availability) {
                OfferingAvailability.AVAILABLE -> "AVAILABLE"
                OfferingAvailability.UNAVAILABLE -> "UNAVAILABLE"
            },
    )

private fun OfferingPrice.toStored(): OfferingPriceJson =
    when (this) {
        is OfferingPrice.Fixed ->
            OfferingPriceJson.Fixed(amount.amount.toStoredDecimal(), amount.currency.toStoredCurrency())
        is OfferingPrice.PerQuantity ->
            OfferingPriceJson.PerQuantity(amount.amount.toStoredDecimal(), amount.currency.toStoredCurrency(), dimension.value)
        is OfferingPrice.PerDuration ->
            OfferingPriceJson.PerDuration(
                amount.amount.toStoredDecimal(),
                amount.currency.toStoredCurrency(),
                interval.seconds,
                interval.nano,
            )
    }

internal fun toStoredCatalog(
    categories: List<OfferingCategory>,
    offerings: List<Offering>,
): String =
    encodeStored(
        OfferingsCatalogJson.serializer(),
        OfferingsCatalogJson(categories.map { it.toStored() }, offerings.map { it.toStored() }),
    )

internal fun OfferingCategoryJson.restore(): OfferingCategory =
    OfferingCategory(OfferingCategoryKey(key), displayName, description, minimumSelections, maximumSelections)

internal fun OfferingJson.restore(): Offering =
    Offering(
        OfferingKey(key),
        OfferingCategoryKey(category),
        displayName,
        description,
        price?.restore(),
        when (selectionState) {
            "ENABLED" -> OfferingSelectionState.ENABLED
            "DISABLED" -> OfferingSelectionState.DISABLED
            else -> error("Unsupported offering selection state: $selectionState")
        },
        when (availability) {
            "AVAILABLE" -> OfferingAvailability.AVAILABLE
            "UNAVAILABLE" -> OfferingAvailability.UNAVAILABLE
            else -> error("Unsupported offering availability: $availability")
        },
    )

private fun OfferingPriceJson.restore(): OfferingPrice =
    when (this) {
        is OfferingPriceJson.Fixed -> OfferingPrice.Fixed(Money(amount.toDecimal(), currency.toCurrency()))
        is OfferingPriceJson.PerQuantity ->
            OfferingPrice.PerQuantity(Money(amount.toDecimal(), currency.toCurrency()), QuantityDimension(dimension))
        is OfferingPriceJson.PerDuration -> {
            require(nanos in 0..999_999_999) { "Duration nanoseconds out of range: $nanos" }
            OfferingPrice.PerDuration(Money(amount.toDecimal(), currency.toCurrency()), Duration.ofSeconds(seconds, nanos.toLong()))
        }
    }

internal fun restoreStoredCatalog(
    what: String,
    json: String,
): Pair<List<OfferingCategory>, List<Offering>> =
    restoreStored(what, OfferingsCatalogJson.serializer(), json) { catalog ->
        catalog.categories.map { it.restore() } to catalog.offerings.map { it.restore() }
    }
