package io.github.castab.commerce.deposit

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.payment.FinancialDocumentReconciliation
import java.util.UUID

/**
 * One immutable revision of a financial lineage's approved deposit terms. Identity is
 * [documentId] plus [revision]. Replacing or reactivating appends Active; withdrawing an
 * Active appends Withdrawn. No revision embeds a predecessor or owns application policy.
 */
sealed class DepositRequirement private constructor(
    val documentId: UUID,
    val revision: DepositRequirementRevision,
) {
    /** Absent only for the first revision. */
    val previousRevision: DepositRequirementRevision?
        get() = revision.previous

    final override fun equals(other: Any?): Boolean =
        this === other ||
            other is DepositRequirement &&
            documentId == other.documentId &&
            revision == other.revision &&
            when (this) {
                is Active ->
                    other is Active &&
                        approvalReference == other.approvalReference &&
                        terms == other.terms &&
                        requiredAmount == other.requiredAmount
                is Withdrawn -> other is Withdrawn
            }

    final override fun hashCode(): Int {
        var result = 31 * documentId.hashCode() + revision.hashCode()
        if (this is Active) {
            result = 31 * result + approvalReference.hashCode()
            result = 31 * result + terms.hashCode()
            result = 31 * result + requiredAmount.hashCode()
        }
        return result
    }

    final override fun toString(): String =
        when (this) {
            is Active ->
                "Active(documentId=$documentId, revision=$revision, approvalReference=$approvalReference, " +
                    "terms=$terms, requiredAmount=$requiredAmount)"
            is Withdrawn -> "Withdrawn(documentId=$documentId, revision=$revision)"
        }

    /** Appends approved terms for the same lineage against this exact snapshot. */
    fun activate(
        document: FinancialDocument,
        terms: DepositTerms,
    ): Active {
        require(document.id == documentId) { "Deposit requirement and approval document lineages must match" }
        return Active.approve(document, terms, revision.next())
    }

    /** Approved terms and their frozen resolved amount; later document versions never recalculate it. */
    class Active private constructor(
        revision: DepositRequirementRevision,
        val approvalReference: FinancialDocumentReference,
        val terms: DepositTerms,
        val requiredAmount: Money,
    ) : DepositRequirement(approvalReference.id, revision) {
        /**
         * Derives current satisfaction exactly as netApplied >= requiredAmount. Requires the
         * same lineage and currency, but permits reconciliation of a later document version.
         * Refunds and allocation unwinds can make a satisfied requirement unsatisfied.
         */
        fun isSatisfiedBy(reconciliation: FinancialDocumentReconciliation): Boolean {
            require(reconciliation.documentReference.id == documentId) { "Deposit requirement and reconciliation lineages must match" }
            require(reconciliation.currency == requiredAmount.currency) { "Deposit requirement and reconciliation currencies must match" }
            return reconciliation.netApplied.amount >= requiredAmount.amount
        }

        /** Withdraws this approval by appending its immediate successor without copying terms. */
        fun withdraw(): Withdrawn = Withdrawn.restore(documentId, revision.next())

        companion object {
            /** Approves the first requirement of a lineage. Any financial-document stage is eligible. */
            @JvmStatic
            fun create(
                document: FinancialDocument,
                terms: DepositTerms,
            ): Active = approve(document, terms, DepositRequirementRevision.INITIAL)

            /**
             * Restores a revision against its original approval snapshot, checking that the
             * stored frozen amount equals the terms' resolution, including decimal scale.
             * The snapshot is used for validation only and is never retained.
             */
            @JvmStatic
            fun restore(
                document: FinancialDocument,
                revision: DepositRequirementRevision,
                terms: DepositTerms,
                requiredAmount: Money,
            ): Active {
                val approved = approve(document, terms, revision)
                require(approved.requiredAmount == requiredAmount) { "Stored deposit amount disagrees with approved terms" }
                return approved
            }

            @JvmSynthetic
            internal fun approve(
                document: FinancialDocument,
                terms: DepositTerms,
                revision: DepositRequirementRevision,
            ): Active = Active(revision, document.reference, terms, terms.resolve(document))
        }
    }

    /** Historical withdrawal of the immediately preceding Active revision; contains no terms. */
    class Withdrawn private constructor(
        documentId: UUID,
        revision: DepositRequirementRevision,
    ) : DepositRequirement(documentId, revision) {
        companion object {
            /** Restores a withdrawal; persistence must additionally verify that its predecessor is Active. */
            @JvmStatic
            fun restore(
                documentId: UUID,
                revision: DepositRequirementRevision,
            ): Withdrawn {
                require(revision.number > 1) { "A withdrawal must follow an active requirement" }
                return Withdrawn(documentId, revision)
            }
        }
    }
}
