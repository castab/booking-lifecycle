package io.github.castab.commerce.runtime.financial

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.payment.PaymentAllocation
import io.github.castab.commerce.payment.RefundAllocation
import io.github.castab.commerce.payment.RefundRecord
import java.time.Instant
import java.util.UUID

/**
 * One part of a refund that unwinds value applied by the persisted payment allocation
 * [paymentAllocationId], given to [FinancialLedger.recordRefund]. The ledger loads the
 * allocation and creates the domain [RefundAllocation] with the caller's [id], [amount],
 * and [allocatedAt]. The caller chooses which allocations a refund unwinds; the runtime
 * never selects them.
 */
data class RefundAllocationPortion(
    /** The identity of the resulting [RefundAllocation], chosen by the application. */
    val id: UUID,
    /** The [PaymentAllocation.id] whose applied value is unwound. */
    val paymentAllocationId: UUID,
    /** The amount unwound, in the payment's currency. */
    val amount: Money,
    /** When the refunded amount was attributed to the allocation. */
    val allocatedAt: Instant,
)

/** A refund and the refund allocations recorded with it, in one transaction. */
data class RecordedRefund(
    val refund: RefundRecord,
    /** Empty when the whole refund came from the payment's unapplied value. */
    val allocations: List<RefundAllocation>,
)
