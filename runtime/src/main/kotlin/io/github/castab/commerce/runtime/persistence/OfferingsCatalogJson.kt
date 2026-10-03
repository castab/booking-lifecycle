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
 * The stored contents of one offerings catalog at its current revision, the `catalog` column
 * of `commerce.offerings_catalogs`. Array order is category and offering order. The retired
 * arrays hold the last representation of every key that was once present and is not now,
 * with the revision it was last present in.
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
 *                  "availability": "AVAILABLE" | "UNAVAILABLE",
 *                  "badge": null | "...", "statusNote": null | "..."}],
 *  "retiredCategories": [{"lastSeenRevision": 3, "category": {...a category...}}],
 *  "retiredOfferings":  [{"lastSeenRevision": 3, "offering": {...an offering...}}]}
 * ```
 *
 * Every property is required, with `null` written explicitly where a value is absent. Price
 * kinds and the enums are explicit names, never class names or ordinals, and money is a
 * plain decimal string with its currency code. The catalog id and revision are columns of the
 * row and are not repeated here.
 */
@Serializable
internal class OfferingsCatalogJson(
    val categories: List<OfferingCategoryJson>,
    val offerings: List<OfferingJson>,
    val retiredCategories: List<RetiredOfferingCategoryJson>,
    val retiredOfferings: List<RetiredOfferingJson>,
)

@Serializable
internal class RetiredOfferingCategoryJson(
    @Serializable(with = StrictIntSerializer::class) val lastSeenRevision: Int,
    val category: OfferingCategoryJson,
)

@Serializable
internal class RetiredOfferingJson(
    @Serializable(with = StrictIntSerializer::class) val lastSeenRevision: Int,
    val offering: OfferingJson,
)

/** A catalog's current contents and the last representations of its retired keys. */
internal class StoredCatalog(
    val categories: List<OfferingCategory>,
    val offerings: List<Offering>,
    val retiredCategories: List<Pair<Int, OfferingCategory>>,
    val retiredOfferings: List<Pair<Int, Offering>>,
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
    val badge: String?,
    val statusNote: String?,
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
        badge = badge,
        statusNote = statusNote,
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

internal fun StoredCatalog.toStored(): String =
    encodeStored(
        OfferingsCatalogJson.serializer(),
        OfferingsCatalogJson(
            categories.map { it.toStored() },
            offerings.map { it.toStored() },
            retiredCategories.map { (revision, category) -> RetiredOfferingCategoryJson(revision, category.toStored()) },
            retiredOfferings.map { (revision, offering) -> RetiredOfferingJson(revision, offering.toStored()) },
        ),
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
        badge,
        statusNote,
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
): StoredCatalog =
    restoreStored(what, OfferingsCatalogJson.serializer(), json) { catalog ->
        StoredCatalog(
            catalog.categories.map { it.restore() },
            catalog.offerings.map { it.restore() },
            catalog.retiredCategories.map { it.lastSeenRevision to it.category.restore() },
            catalog.retiredOfferings.map { it.lastSeenRevision to it.offering.restore() },
        )
    }
