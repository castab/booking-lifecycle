package io.github.castab.commerce.runtime.offering

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingPrice
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.QuantityDimension
import io.github.castab.commerce.runtime.operation.validating
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Duration
import java.time.format.DateTimeParseException
import java.util.Currency

@Serializable
data class OfferingPriceDto(
    val kind: String,
    val amount: String,
    val currency: String,
    val dimension: String? = null,
    val interval: String? = null,
) {
    fun toDomain(): OfferingPrice =
        validating {
            val money = Money(BigDecimal(amount), Currency.getInstance(currency))
            when (kind) {
                "FIXED" -> {
                    require(dimension == null && interval == null) { "FIXED price must not have a dimension or interval" }
                    OfferingPrice.Fixed(money)
                }
                "PER_QUANTITY" -> {
                    require(dimension != null && interval == null) { "PER_QUANTITY price requires only a dimension" }
                    OfferingPrice.PerQuantity(money, QuantityDimension(dimension))
                }
                "PER_DURATION" -> {
                    require(interval != null && dimension == null) { "PER_DURATION price requires only an interval" }
                    val duration =
                        try {
                            Duration.parse(interval)
                        } catch (e: DateTimeParseException) {
                            throw IllegalArgumentException("Invalid duration price interval", e)
                        }
                    OfferingPrice.PerDuration(money, duration)
                }
                else -> throw IllegalArgumentException("Unknown offering price kind: $kind")
            }
        }
}

@Serializable
data class OfferingDto(
    val key: String,
    val category: String,
    val displayName: String,
    val description: String? = null,
    val price: OfferingPriceDto? = null,
) {
    fun toDomain(): Offering =
        validating { Offering(OfferingKey(key), OfferingCategoryKey(category), displayName, description, price?.toDomain()) }
}

@Serializable
data class OfferingCategoryDto(
    val key: String,
    val displayName: String,
    val description: String? = null,
    val minimumSelections: Int = 0,
    val maximumSelections: Int? = null,
) {
    fun toDomain(): OfferingCategory =
        validating { OfferingCategory(OfferingCategoryKey(key), displayName, description, minimumSelections, maximumSelections) }
}

/** Editable offering properties. Identity comes only from the route/operation key. */
@Serializable
data class OfferingMutationDto(
    val category: String,
    val displayName: String,
    val description: String? = null,
    val price: OfferingPriceDto? = null,
) {
    fun toDomain(key: OfferingKey): Offering = OfferingDto(key.value, category, displayName, description, price).toDomain()
}

/** Editable category properties. Identity comes only from the route/operation key. */
@Serializable
data class OfferingCategoryMutationDto(
    val displayName: String,
    val description: String? = null,
    val minimumSelections: Int = 0,
    val maximumSelections: Int? = null,
) {
    fun toDomain(key: OfferingCategoryKey): OfferingCategory =
        OfferingCategoryDto(key.value, displayName, description, minimumSelections, maximumSelections).toDomain()
}

/** A retirement still advances the catalog timeline. */
@Serializable
data class CatalogRevisionDto(
    val revision: Int,
)

@Serializable
data class RetiredOfferingDto(
    val lastSeenRevision: Int,
    val offering: OfferingDto,
)

@Serializable
data class RetiredCategoryDto(
    val lastSeenRevision: Int,
    val category: OfferingCategoryDto,
)

@Serializable
data class RetiredOfferingsDto(
    val revision: Int,
    val offerings: List<RetiredOfferingDto>,
)

@Serializable
data class RetiredCategoriesDto(
    val revision: Int,
    val categories: List<RetiredCategoryDto>,
)

@Serializable
data class CatalogCategoryDto(
    val key: String,
    val displayName: String,
    val description: String? = null,
    val minimumSelections: Int,
    val maximumSelections: Int? = null,
    val offerings: List<OfferingDto>,
)

@Serializable
data class OfferingsCatalogDto(
    val catalogId: String,
    val revision: Int,
    val previousRevision: Int? = null,
    val categories: List<CatalogCategoryDto>,
)

@Serializable
data class CategoriesDto(
    val revision: Int,
    val categories: List<OfferingCategoryDto>,
)

@Serializable
data class CategoryDto(
    val revision: Int,
    val category: OfferingCategoryDto,
)

@Serializable
data class CategoryOfferingsDto(
    val revision: Int,
    val category: OfferingCategoryDto,
    val offerings: List<OfferingDto>,
)

@Serializable
data class OfferingsDto(
    val revision: Int,
    val offerings: List<OfferingDto>,
)

@Serializable
data class OfferingResultDto(
    val revision: Int,
    val offering: OfferingDto,
)

private fun OfferingCategory.dto(): OfferingCategoryDto =
    OfferingCategoryDto(key.value, displayName, description, minimumSelections, maximumSelections)

private fun OfferingPrice.dto(): OfferingPriceDto {
    val money =
        when (this) {
            is OfferingPrice.Fixed -> amount
            is OfferingPrice.PerQuantity -> amount
            is OfferingPrice.PerDuration -> amount
        }
    return OfferingPriceDto(
        kind =
            when (this) {
                is OfferingPrice.Fixed -> "FIXED"
                is OfferingPrice.PerQuantity -> "PER_QUANTITY"
                is OfferingPrice.PerDuration -> "PER_DURATION"
            },
        amount = money.amount.toPlainString(),
        currency = money.currency.currencyCode,
        dimension = (this as? OfferingPrice.PerQuantity)?.dimension?.value,
        interval = (this as? OfferingPrice.PerDuration)?.interval?.toString(),
    )
}

private fun Offering.dto(): OfferingDto = OfferingDto(key.value, category.value, displayName, description, price?.dto())

fun OfferingsSnapshot.dto(): OfferingsCatalogDto =
    OfferingsCatalogDto(
        catalogId.value.toString(),
        revision.number,
        previousRevision?.number,
        categories.map { category ->
            val item = category.dto()
            CatalogCategoryDto(
                item.key,
                item.displayName,
                item.description,
                item.minimumSelections,
                item.maximumSelections,
                offeringsIn(category.key).map(Offering::dto),
            )
        },
    )

fun CatalogResult<List<OfferingCategory>>.categoriesDto(): CategoriesDto = CategoriesDto(reference.revision.number, value.map { it.dto() })

fun CatalogResult<OfferingCategory>.categoryDto(): CategoryDto = CategoryDto(reference.revision.number, value.dto())

fun CatalogResult<CategoryOfferings>.categoryOfferingsDto(): CategoryOfferingsDto =
    CategoryOfferingsDto(reference.revision.number, value.category.dto(), value.offerings.map { it.dto() })

fun CatalogResult<List<Offering>>.offeringsDto(): OfferingsDto = OfferingsDto(reference.revision.number, value.map { it.dto() })

fun CatalogResult<Offering>.offeringDto(): OfferingResultDto = OfferingResultDto(reference.revision.number, value.dto())

fun CatalogResult<List<CatalogResult<Offering>>>.retiredOfferingsDto(): RetiredOfferingsDto =
    RetiredOfferingsDto(reference.revision.number, value.map { RetiredOfferingDto(it.reference.revision.number, it.value.dto()) })

fun CatalogResult<List<CatalogResult<OfferingCategory>>>.retiredCategoriesDto(): RetiredCategoriesDto =
    RetiredCategoriesDto(reference.revision.number, value.map { RetiredCategoryDto(it.reference.revision.number, it.value.dto()) })
