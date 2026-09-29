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
 * them, and no status is stored. [FinancialLedger] builds a history from one coherent read
 * of the database and only the ledger can construct one, so [reconciliation] is never
 * derived from a different set of facts than the ones in this object. The runtime does not
 * persist allocation reversals, so none contribute.
 *
 * Every list is ordered for presentation, not for validity: reconciliation ignores the
 * order of records. Ties on the timestamp are broken by the record's [java.util.UUID]
 * ordering, which is arbitrary but stable.
 */
@ConsistentCopyVisibility
data class PaymentHistory internal constructor(
    /** The payment fact. */
    val payment: PaymentRecord,
    /**
     * Every allocation of the payment, to any document, ascending by
     * [PaymentAllocation.allocatedAt], then [PaymentAllocation.id]. Includes allocations
     * later unwound by refunds.
     */
    val allocations: List<PaymentAllocation>,
    /** Every refund of the payment, ascending by [RefundRecord.refundedAt], then [RefundRecord.id]. */
    val refunds: List<RefundRecord>,
    /**
     * The refund allocations of [refunds]: which allocations each refund unwound. Ascending by
     * [RefundAllocation.allocatedAt], then [RefundAllocation.id]. Empty when every refund came
     * from unapplied value.
     */
    val refundAllocations: List<RefundAllocation>,
    /** Settlement of [payment] derived from the facts above, never stored. */
    val reconciliation: PaymentReconciliation,
)
