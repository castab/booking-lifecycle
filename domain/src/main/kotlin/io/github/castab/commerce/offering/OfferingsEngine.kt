package io.github.castab.commerce.offering

import io.github.castab.commerce.financial.LineItem
import java.util.Collections

/** One category block in a candidate selection, including an explicitly empty choice. */
class OfferingCategorySelection(
    val category: OfferingCategoryKey,
    offerings: List<OfferingKey>,
) {
    val offerings: List<OfferingKey> = Collections.unmodifiableList(ArrayList(offerings))

    override fun equals(other: Any?): Boolean =
        other is OfferingCategorySelection && category == other.category && offerings == other.offerings

    override fun hashCode(): Int = 31 * category.hashCode() + offerings.hashCode()
}

/** Candidate choices in submitted order; validation requires a snapshot. */
class OfferingSelections(
    categories: List<OfferingCategorySelection>,
) {
    val categories: List<OfferingCategorySelection> = Collections.unmodifiableList(ArrayList(categories))

    override fun equals(other: Any?): Boolean = other is OfferingSelections && categories == other.categories

    override fun hashCode(): Int = categories.hashCode()
}

/** Applications may define additional violations with their own stable codes. */
interface OfferingsViolation {
    val code: String
}

/** Snapshot-dependent membership, selection eligibility, and cardinality problems. */
sealed interface StructuralOfferingsViolation : OfferingsViolation {
    data class UnknownCategory(
        val category: OfferingCategoryKey,
    ) : StructuralOfferingsViolation {
        override val code = "UNKNOWN_CATEGORY"
    }

    data class UnknownOffering(
        val category: OfferingCategoryKey,
        val offering: OfferingKey,
    ) : StructuralOfferingsViolation {
        override val code = "UNKNOWN_OFFERING"
    }

    data class DisabledOffering(
        val category: OfferingCategoryKey,
        val offering: OfferingKey,
    ) : StructuralOfferingsViolation {
        override val code = "OFFERING_DISABLED"
    }

    data class UnavailableOffering(
        val category: OfferingCategoryKey,
        val offering: OfferingKey,
    ) : StructuralOfferingsViolation {
        override val code = "OFFERING_UNAVAILABLE"
    }

    data class OfferingInWrongCategory(
        val category: OfferingCategoryKey,
        val offering: OfferingKey,
    ) : StructuralOfferingsViolation {
        override val code = "OFFERING_IN_WRONG_CATEGORY"
    }

    data class TooFewSelections(
        val category: OfferingCategoryKey,
        val minimum: Int,
        val actual: Int,
    ) : StructuralOfferingsViolation {
        override val code = "TOO_FEW_SELECTIONS"
    }

    data class TooManySelections(
        val category: OfferingCategoryKey,
        val maximum: Int,
        val actual: Int,
    ) : StructuralOfferingsViolation {
        override val code = "TOO_MANY_SELECTIONS"
    }

    data class DuplicateCategory(
        val category: OfferingCategoryKey,
    ) : StructuralOfferingsViolation {
        override val code = "DUPLICATE_CATEGORY"
    }

    data class DuplicateOffering(
        val category: OfferingCategoryKey,
        val offering: OfferingKey,
    ) : StructuralOfferingsViolation {
        override val code = "DUPLICATE_OFFERING"
    }
}

/** Results supplied by application policy after structural validation succeeds. */
sealed interface OfferingsPolicyResult {
    class Accepted(
        lineItems: List<LineItem>,
    ) : OfferingsPolicyResult {
        val lineItems: List<LineItem> = Collections.unmodifiableList(ArrayList(lineItems))
    }

    class Rejected(
        violations: List<OfferingsViolation>,
    ) : OfferingsPolicyResult {
        val violations: List<OfferingsViolation> = Collections.unmodifiableList(ArrayList(violations))

        init {
            require(this.violations.isNotEmpty()) { "A rejected evaluation must have violations" }
        }
    }
}

/** The exact snapshot and selections evaluated, with financial lines produced by application policy. */
class OfferingsEvaluation internal constructor(
    val snapshot: OfferingsSnapshotReference,
    val selections: OfferingSelections,
    lineItems: List<LineItem>,
) {
    val lineItems: List<LineItem> = Collections.unmodifiableList(ArrayList(lineItems))

    init {
        require(this.lineItems.isNotEmpty()) { "An accepted evaluation must contain at least one line item" }
        require(
            this.lineItems
                .map { it.id }
                .distinct()
                .size == this.lineItems.size,
        ) { "Evaluation contains duplicate line item ids" }
        require(
            this.lineItems
                .map { it.currency }
                .distinct()
                .size == 1,
        ) { "Evaluation mixes line item currencies" }
    }
}

sealed interface OfferingsEvaluationResult {
    data class Accepted(
        val evaluation: OfferingsEvaluation,
    ) : OfferingsEvaluationResult

    class Rejected(
        violations: List<OfferingsViolation>,
    ) : OfferingsEvaluationResult {
        val violations: List<OfferingsViolation> = Collections.unmodifiableList(ArrayList(violations))

        init {
            require(this.violations.isNotEmpty()) { "A rejected evaluation must have violations" }
        }
    }
}

/** Applies common cardinality, membership, and selection eligibility checks before application policy. */
abstract class OfferingsEngine<C> {
    fun evaluate(
        snapshot: OfferingsSnapshot,
        selections: OfferingSelections,
        context: C,
    ): OfferingsEvaluationResult {
        val violations = validate(snapshot, selections)
        if (violations.isNotEmpty()) return OfferingsEvaluationResult.Rejected(violations)
        return when (val result = evaluateValid(snapshot, selections, context)) {
            is OfferingsPolicyResult.Accepted ->
                OfferingsEvaluationResult.Accepted(OfferingsEvaluation(snapshot.reference, selections, result.lineItems))
            is OfferingsPolicyResult.Rejected -> OfferingsEvaluationResult.Rejected(result.violations)
        }
    }

    protected abstract fun evaluateValid(
        snapshot: OfferingsSnapshot,
        selections: OfferingSelections,
        context: C,
    ): OfferingsPolicyResult

    /** Reports problems in submitted block order, then missing required categories in snapshot order. */
    private fun validate(
        snapshot: OfferingsSnapshot,
        selections: OfferingSelections,
    ): List<StructuralOfferingsViolation> {
        val violations = mutableListOf<StructuralOfferingsViolation>()
        val selectedCategories = mutableSetOf<OfferingCategoryKey>()
        selections.categories.forEach { block ->
            if (!selectedCategories.add(block.category)) violations += StructuralOfferingsViolation.DuplicateCategory(block.category)
            val category = snapshot.category(block.category)
            if (category == null) violations += StructuralOfferingsViolation.UnknownCategory(block.category)
            val selectedOfferings = mutableSetOf<OfferingKey>()
            block.offerings.forEach { key ->
                if (!selectedOfferings.add(key)) violations += StructuralOfferingsViolation.DuplicateOffering(block.category, key)
                val offering = snapshot.offering(key)
                when {
                    offering == null -> violations += StructuralOfferingsViolation.UnknownOffering(block.category, key)
                    offering.category != block.category ->
                        violations +=
                            StructuralOfferingsViolation.OfferingInWrongCategory(block.category, key)
                    offering.selectionState == OfferingSelectionState.DISABLED ->
                        violations += StructuralOfferingsViolation.DisabledOffering(block.category, key)
                    offering.availability == OfferingAvailability.UNAVAILABLE ->
                        violations += StructuralOfferingsViolation.UnavailableOffering(block.category, key)
                }
            }
            if (category != null) {
                val count = block.offerings.size
                if (count < category.minimumSelections) {
                    violations += StructuralOfferingsViolation.TooFewSelections(block.category, category.minimumSelections, count)
                }
                val max = category.maximumSelections
                if (max != null && count > max) violations += StructuralOfferingsViolation.TooManySelections(block.category, max, count)
            }
        }
        snapshot.categories.forEach { category ->
            if (category.key !in selectedCategories && category.minimumSelections > 0) {
                violations += StructuralOfferingsViolation.TooFewSelections(category.key, category.minimumSelections, 0)
            }
        }
        return violations
    }
}
