package io.github.castab.commerce.runtime.financial

import io.github.castab.commerce.payment.PaymentAllocation
import io.github.castab.commerce.payment.PaymentReconciliation
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.payment.RefundAllocation
import io.github.castab.commerce.payment.RefundRecord

/**
 * The complete persisted history of one payment: the immutable [payment] fact, every fact
 * recorded against it, and the [reconciliation] derived from exactly those facts.
 *
 * A history always describes the whole payment, however it was discovered. A payment found
 * through one financial-document lineage still carries its allocations to every other
 * lineage, because its [reconciliation] depends on all of them. A caller that renders one
 * document selects the relevant allocations, for example those whose
 * `financialDocumentReference.id` is that document's id.
 *
 * The facts are the authoritative domain records; nothing here is a copy or a summary of
 * them, and no status is stored. A `PaymentHistory` cannot be publicly constructed, and
 * `copy()` is not public, so a caller cannot pair one set of facts with a reconciliation
 * of another. The runtime builds it from persisted facts and derives [reconciliation] from
 * those same facts. The lists are unmodifiable snapshots: casting one to a mutable list
 * and mutating it throws `UnsupportedOperationException`. The runtime does not persist
 * allocation reversals, so none contribute.
 *
 * Every list is ordered for presentation, not for validity: reconciliation ignores the
 * order of records. Ties on the timestamp are broken by the record's [java.util.UUID]
 * ordering, which is arbitrary but stable.
 */
@ConsistentCopyVisibility
data class PaymentHistory private constructor(
    /** The payment fact. */
    val payment: PaymentRecord,
    /**
     * Every allocation of the payment, to any document, ascending by
     * [PaymentAllocation.allocatedAt], then [PaymentAllocation.id]. Includes allocations
     * later unwound by refunds. Unmodifiable.
     */
    val allocations: List<PaymentAllocation>,
    /**
     * Every refund of the payment, ascending by [RefundRecord.refundedAt], then
     * [RefundRecord.id]. Unmodifiable.
     */
    val refunds: List<RefundRecord>,
    /**
     * The refund allocations of [refunds]: which allocations each refund unwound. Ascending by
     * [RefundAllocation.allocatedAt], then [RefundAllocation.id]. Empty when every refund came
     * from unapplied value. Unmodifiable.
     */
    val refundAllocations: List<RefundAllocation>,
    /** Settlement of [payment] derived from the facts above, never stored. */
    val reconciliation: PaymentReconciliation,
) {
    internal companion object {
        /**
         * Builds the history of [payment] from its persisted facts. Sorts and copies them into
         * unmodifiable lists, then derives the reconciliation from those same lists.
         */
        @JvmSynthetic
        fun from(
            payment: PaymentRecord,
            allocations: Collection<PaymentAllocation>,
            refunds: Collection<RefundRecord>,
            refundAllocations: Collection<RefundAllocation>,
        ): PaymentHistory {
            val sortedAllocations = allocations.sortedWith(compareBy<PaymentAllocation> { it.allocatedAt }.thenBy { it.id })
            val sortedRefunds = refunds.sortedWith(compareBy<RefundRecord> { it.refundedAt }.thenBy { it.id })
            val sortedRefundAllocations =
                refundAllocations.sortedWith(compareBy<RefundAllocation> { it.allocatedAt }.thenBy { it.id })
            // The runtime does not persist allocation reversals.
            val reconciliation =
                PaymentReconciliation.reconcile(payment, sortedAllocations, emptyList(), sortedRefunds, sortedRefundAllocations)
            return PaymentHistory(
                payment,
                java.util.List.copyOf(sortedAllocations),
                java.util.List.copyOf(sortedRefunds),
                java.util.List.copyOf(sortedRefundAllocations),
                reconciliation,
            )
        }
    }
}
