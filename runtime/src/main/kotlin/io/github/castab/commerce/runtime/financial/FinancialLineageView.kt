package io.github.castab.commerce.runtime.financial

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.payment.FinancialDocumentReconciliation
import java.time.Instant

/** Objective financial event times for one lineage; receipt and standalone refund times are excluded. */
class FinancialLineageActivity internal constructor(
    val latestDocumentVersionAt: Instant,
    val latestDepositRequirementAt: Instant?,
    val latestPaymentAllocationAt: Instant?,
    val latestRefundAllocationAt: Instant?,
) {
    /** Exactly the maximum of the four available constituent event times. */
    val latestFinancialActivityAt: Instant =
        listOfNotNull(latestDocumentVersionAt, latestDepositRequirementAt, latestPaymentAllocationAt, latestRefundAllocationAt).max()
}

/** A current financial lineage read from one transaction, with settlement and satisfaction derived. */
class FinancialLineageView private constructor(
    val latestVersion: FinancialDocumentVersion,
    val reconciliation: FinancialDocumentReconciliation,
    /** Null means never configured; a Withdrawn revision retains the distinction from no history. */
    val depositRequirement: DepositRequirementVersion?,
    val activity: FinancialLineageActivity,
) {
    /** Null for absent or withdrawn requirements; otherwise netApplied >= the frozen required amount. */
    val depositSatisfied: Boolean? = (depositRequirement?.requirement as? DepositRequirement.Active)?.isSatisfiedBy(reconciliation)

    internal companion object {
        fun from(
            latestVersion: FinancialDocumentVersion,
            reconciliation: FinancialDocumentReconciliation,
            depositRequirement: DepositRequirementVersion?,
            activity: FinancialLineageActivity,
        ): FinancialLineageView = FinancialLineageView(latestVersion, reconciliation, depositRequirement, activity)
    }
}
