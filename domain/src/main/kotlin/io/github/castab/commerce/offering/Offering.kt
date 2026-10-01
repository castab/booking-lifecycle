package io.github.castab.commerce.offering

import io.github.castab.commerce.financial.Money
import java.time.Duration
import java.util.UUID

/** Durable natural identity within a catalog. Historical use reserves the key even after retirement. */
@JvmInline
value class OfferingKey(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value == value.trim() && value.none(Char::isWhitespace)) {
            "Offering key must be nonblank and contain no whitespace"
        }
    }
}

/** Durable natural category identity within a catalog; historical use reserves it even after retirement. */
@JvmInline
value class OfferingCategoryKey(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value == value.trim() && value.none(Char::isWhitespace)) {
            "Offering category key must be nonblank and contain no whitespace"
        }
    }
}

/** Application-named unit for a quantity rate. Its business meaning stays with the application. */
@JvmInline
value class QuantityDimension(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value == value.trim() && value.none(Char::isWhitespace)) {
            "Quantity dimension must be nonblank and contain no whitespace"
        }
    }
}

/** Stable identity shared by all revisions of one offerings catalog. */
@JvmInline
value class OfferingsCatalogId(
    val value: UUID,
)

/** Descriptive price metadata interpreted by an application engine, never a pricing rule. */
sealed interface OfferingPrice {
    data class Fixed(
        val amount: Money,
    ) : OfferingPrice

    data class PerQuantity(
        val amount: Money,
        val dimension: QuantityDimension,
    ) : OfferingPrice

    data class PerDuration(
        val amount: Money,
        val interval: Duration,
    ) : OfferingPrice {
        init {
            require(!interval.isZero && !interval.isNegative) { "Duration price interval must be positive" }
        }
    }
}

/** A group of offerings and its generic selection cardinality. */
data class OfferingCategory(
    val key: OfferingCategoryKey,
    val displayName: String,
    val description: String? = null,
    val minimumSelections: Int = 0,
    val maximumSelections: Int? = null,
) {
    init {
        require(displayName.isNotBlank()) { "Category $key must have a nonblank display name" }
        require(minimumSelections >= 0) { "Category $key minimum selections must be nonnegative" }
        require(maximumSelections == null || maximumSelections > 0 && maximumSelections >= minimumSelections) {
            "Category $key maximum selections must be positive and at least its minimum"
        }
    }
}

/** Deliberate catalog configuration, independent of retirement and temporary fulfillment availability. */
enum class OfferingSelectionState {
    ENABLED,
    DISABLED,
}

/** Whether an offering can currently be fulfilled, independently of its selection configuration. */
enum class OfferingAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

/**
 * One commercial item, service, or choice in exactly one category at a catalog revision.
 * Selection configuration and fulfillment availability are independent; all four combinations
 * are valid. Disabled and unavailable offerings remain readable but cannot be selected.
 */
data class Offering(
    val key: OfferingKey,
    val category: OfferingCategoryKey,
    val displayName: String,
    val description: String? = null,
    val price: OfferingPrice? = null,
    val selectionState: OfferingSelectionState = OfferingSelectionState.ENABLED,
    val availability: OfferingAvailability = OfferingAvailability.AVAILABLE,
) {
    init {
        require(displayName.isNotBlank()) { "Offering $key must have a nonblank display name" }
    }
}
