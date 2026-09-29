package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.ExternalPaymentReference
import io.github.castab.commerce.payment.ExternalRefundReference
import io.github.castab.commerce.payment.PaymentAllocation
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentReconciliation
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.payment.RefundAllocation
import io.github.castab.commerce.payment.RefundRecord
import io.github.castab.commerce.runtime.operation.CommerceFailure
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.sql.ResultSet
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * Immutable payment, allocation, refund, and refund-allocation facts in caller-owned
 * transactions. Every append locks the payment row and checks the payment's complete
 * persisted history through `PaymentReconciliation` before it writes.
 */
interface PaymentRepository {
    fun insertPayment(
        transaction: Transaction,
        payment: PaymentRecord,
    )

    fun retrievePayment(
        transaction: Transaction,
        id: UUID,
    ): PaymentRecord?

    /** Serializes allocation and refund attempts for one payment until the caller commits. */
    fun lockPayment(
        transaction: Transaction,
        id: UUID,
    ): PaymentRecord?

    fun insertAllocation(
        transaction: Transaction,
        allocation: PaymentAllocation,
    )

    fun retrieveAllocation(
        transaction: Transaction,
        id: UUID,
    ): PaymentAllocation?

    fun allocationsForPayment(
        transaction: Transaction,
        paymentId: UUID,
    ): List<PaymentAllocation>

    fun allocationsForLineage(
        transaction: Transaction,
        documentId: UUID,
    ): List<PaymentAllocation>

    /**
     * Appends [refund] and the [refundAllocations] that unwind applied value, as one fact.
     * They are checked together against the payment's complete history, so a refund that
     * needs its unwinds to be valid is never stored without them. Every refund allocation
     * must belong to [refund]; none is needed for money refunded from unapplied value.
     */
    fun insertRefund(
        transaction: Transaction,
        refund: RefundRecord,
        refundAllocations: List<RefundAllocation>,
    )

    fun retrieveRefund(
        transaction: Transaction,
        id: UUID,
    ): RefundRecord?

    fun refundsForPayment(
        transaction: Transaction,
        paymentId: UUID,
    ): List<RefundRecord>

    fun retrieveRefundAllocation(
        transaction: Transaction,
        id: UUID,
    ): RefundAllocation?

    /** The refund allocations of the payment's refunds. */
    fun refundAllocationsForPayment(
        transaction: Transaction,
        paymentId: UUID,
    ): List<RefundAllocation>

    /** The refund allocations that unwind allocations to any version of the document. */
    fun refundAllocationsForLineage(
        transaction: Transaction,
        documentId: UUID,
    ): List<RefundAllocation>
}

internal class PostgresPaymentRepository(
    private val documents: FinancialDocumentRepository,
) : PaymentRepository {
    override fun insertPayment(
        transaction: Transaction,
        payment: PaymentRecord,
    ) {
        try {
            transaction.handle
                .createUpdate(
                    """INSERT INTO commerce.payment_records
                       (payment_id, amount, currency, method, received_at_seconds, received_at_nanos,
                        external_provider, external_reference)
                       VALUES (:id, :amount, :currency, :method, :seconds, :nanos, :provider, :reference)""",
                ).bind("id", payment.id)
                .bind("amount", payment.amount.amount)
                .bind("currency", payment.currency.currencyCode)
                .bind("method", payment.method.name)
                .bind("seconds", payment.receivedAt.epochSecond)
                .bind("nanos", payment.receivedAt.nano)
                .bind("provider", payment.externalReference?.provider)
                .bind("reference", payment.externalReference?.reference)
                .execute()
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) throw CommerceFailure.Conflict("Payment id or external reference already exists", e)
            throw e
        }
    }

    override fun retrievePayment(
        transaction: Transaction,
        id: UUID,
    ): PaymentRecord? = payment(transaction, id, false)

    override fun lockPayment(
        transaction: Transaction,
        id: UUID,
    ): PaymentRecord? = payment(transaction, id, true)

    private fun payment(
        transaction: Transaction,
        id: UUID,
        lock: Boolean,
    ): PaymentRecord? =
        transaction.handle
            .createQuery(
                """SELECT payment_id, amount, currency, method, received_at_seconds, received_at_nanos,
                          external_provider, external_reference FROM commerce.payment_records
                   WHERE payment_id = :id ${if (lock) "FOR UPDATE" else ""}""",
            ).bind("id", id)
            .map { rows, _ -> payment(rows) }
            .findOne()
            .orElse(null)

    override fun insertAllocation(
        transaction: Transaction,
        allocation: PaymentAllocation,
    ) {
        val payment =
            lockPayment(transaction, allocation.paymentReference)
                ?: throw CommerceFailure.NotFound("Payment ${allocation.paymentReference} was not found")
        val document =
            documents.retrieveVersion(transaction, allocation.financialDocumentReference)
                ?: throw CommerceFailure.NotFound("Financial document ${allocation.financialDocumentReference} was not found")
        try {
            PaymentAllocation.create(allocation.id, payment, document, allocation.amount, allocation.allocatedAt)
            reconcile(transaction, payment, additionalAllocations = listOf(allocation))
        } catch (e: IllegalArgumentException) {
            throw CommerceFailure.InvariantViolated(e.message ?: "Invalid payment allocation", e)
        }
        try {
            transaction.handle
                .createUpdate(
                    """INSERT INTO commerce.payment_allocations
                       (allocation_id, payment_id, document_id, document_version, amount, currency,
                        allocated_at_seconds, allocated_at_nanos)
                       VALUES (:id, :paymentId, :documentId, :version, :amount, :currency, :seconds, :nanos)""",
                ).bind("id", allocation.id)
                .bind("paymentId", allocation.paymentReference)
                .bind("documentId", allocation.financialDocumentReference.id)
                .bind("version", allocation.financialDocumentReference.version.number)
                .bind("amount", allocation.amount.amount)
                .bind("currency", allocation.currency.currencyCode)
                .bind("seconds", allocation.allocatedAt.epochSecond)
                .bind("nanos", allocation.allocatedAt.nano)
                .execute()
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) throw CommerceFailure.Conflict("Payment allocation ${allocation.id} already exists", e)
            throw e
        }
    }

    override fun retrieveAllocation(
        transaction: Transaction,
        id: UUID,
    ): PaymentAllocation? =
        transaction.handle
            .createQuery("SELECT * FROM commerce.payment_allocations WHERE allocation_id = :id")
            .bind("id", id)
            .map { rows, _ -> allocation(rows) }
            .findOne()
            .orElse(null)

    override fun allocationsForPayment(
        transaction: Transaction,
        paymentId: UUID,
    ): List<PaymentAllocation> =
        transaction.handle
            .createQuery("SELECT * FROM commerce.payment_allocations WHERE payment_id = :id ORDER BY allocation_id")
            .bind("id", paymentId)
            .map { rows, _ -> allocation(rows) }
            .list()

    override fun allocationsForLineage(
        transaction: Transaction,
        documentId: UUID,
    ): List<PaymentAllocation> =
        transaction.handle
            .createQuery("SELECT * FROM commerce.payment_allocations WHERE document_id = :id ORDER BY allocation_id")
            .bind("id", documentId)
            .map { rows, _ -> allocation(rows) }
            .list()

    override fun insertRefund(
        transaction: Transaction,
        refund: RefundRecord,
        refundAllocations: List<RefundAllocation>,
    ) {
        val payment =
            lockPayment(transaction, refund.paymentReference)
                ?: throw CommerceFailure.NotFound("Payment ${refund.paymentReference} was not found")
        // Checked under the payment lock, so a duplicate is a conflict rather than a repeated id
        // in the reconciled history. The primary keys remain the final guard.
        if (retrieveRefund(transaction, refund.id) != null) {
            throw CommerceFailure.Conflict("Refund ${refund.id} already exists")
        }
        val unwound =
            refundAllocations.map { refundAllocation ->
                if (retrieveRefundAllocation(transaction, refundAllocation.id) != null) {
                    throw CommerceFailure.Conflict("Refund allocation ${refundAllocation.id} already exists")
                }
                val allocation =
                    retrieveAllocation(transaction, refundAllocation.paymentAllocationReference)
                        ?: throw CommerceFailure.NotFound(
                            "Payment allocation ${refundAllocation.paymentAllocationReference} was not found",
                        )
                refundAllocation to allocation
            }
        try {
            RefundRecord.create(refund.id, payment, refund.amount, refund.method, refund.refundedAt, refund.externalReference)
            unwound.forEach { (refundAllocation, allocation) ->
                require(refundAllocation.refundReference == refund.id) {
                    "Refund allocation ${refundAllocation.id} belongs to refund ${refundAllocation.refundReference}, " +
                        "not refund ${refund.id}"
                }
                RefundAllocation.create(refundAllocation.id, refund, allocation, refundAllocation.amount, refundAllocation.allocatedAt)
            }
            reconcile(transaction, payment, additionalRefunds = listOf(refund), additionalRefundAllocations = refundAllocations)
        } catch (e: IllegalArgumentException) {
            throw CommerceFailure.InvariantViolated(e.message ?: "Invalid refund", e)
        }
        try {
            transaction.handle
                .createUpdate(
                    """INSERT INTO commerce.refund_records
                       (refund_id, payment_id, amount, currency, method, refunded_at_seconds, refunded_at_nanos,
                        external_provider, external_reference)
                       VALUES (:id, :paymentId, :amount, :currency, :method, :seconds, :nanos, :provider, :reference)""",
                ).bind("id", refund.id)
                .bind("paymentId", refund.paymentReference)
                .bind("amount", refund.amount.amount)
                .bind("currency", refund.currency.currencyCode)
                .bind("method", refund.method.name)
                .bind("seconds", refund.refundedAt.epochSecond)
                .bind("nanos", refund.refundedAt.nano)
                .bind("provider", refund.externalReference?.provider)
                .bind("reference", refund.externalReference?.reference)
                .execute()
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) throw CommerceFailure.Conflict("Refund id or external reference already exists", e)
            throw e
        }
        refundAllocations.forEach { refundAllocation ->
            try {
                transaction.handle
                    .createUpdate(
                        """INSERT INTO commerce.refund_allocations
                           (refund_allocation_id, refund_id, payment_allocation_id, amount, currency,
                            allocated_at_seconds, allocated_at_nanos)
                           VALUES (:id, :refundId, :allocationId, :amount, :currency, :seconds, :nanos)""",
                    ).bind("id", refundAllocation.id)
                    .bind("refundId", refundAllocation.refundReference)
                    .bind("allocationId", refundAllocation.paymentAllocationReference)
                    .bind("amount", refundAllocation.amount.amount)
                    .bind("currency", refundAllocation.currency.currencyCode)
                    .bind("seconds", refundAllocation.allocatedAt.epochSecond)
                    .bind("nanos", refundAllocation.allocatedAt.nano)
                    .execute()
            } catch (e: UnableToExecuteStatementException) {
                if (e.isUniqueViolation()) throw CommerceFailure.Conflict("Refund allocation ${refundAllocation.id} already exists", e)
                throw e
            }
        }
    }

    override fun retrieveRefund(
        transaction: Transaction,
        id: UUID,
    ): RefundRecord? =
        transaction.handle
            .createQuery("SELECT * FROM commerce.refund_records WHERE refund_id = :id")
            .bind("id", id)
            .map { rows, _ -> refund(rows) }
            .findOne()
            .orElse(null)

    override fun refundsForPayment(
        transaction: Transaction,
        paymentId: UUID,
    ): List<RefundRecord> =
        transaction.handle
            .createQuery("SELECT * FROM commerce.refund_records WHERE payment_id = :id ORDER BY refund_id")
            .bind("id", paymentId)
            .map { rows, _ -> refund(rows) }
            .list()

    override fun retrieveRefundAllocation(
        transaction: Transaction,
        id: UUID,
    ): RefundAllocation? =
        transaction.handle
            .createQuery("SELECT * FROM commerce.refund_allocations WHERE refund_allocation_id = :id")
            .bind("id", id)
            .map { rows, _ -> refundAllocation(rows) }
            .findOne()
            .orElse(null)

    override fun refundAllocationsForPayment(
        transaction: Transaction,
        paymentId: UUID,
    ): List<RefundAllocation> =
        transaction.handle
            .createQuery(
                """SELECT ra.* FROM commerce.refund_allocations ra
                   JOIN commerce.refund_records r ON r.refund_id = ra.refund_id
                   WHERE r.payment_id = :id ORDER BY ra.refund_allocation_id""",
            ).bind("id", paymentId)
            .map { rows, _ -> refundAllocation(rows) }
            .list()

    override fun refundAllocationsForLineage(
        transaction: Transaction,
        documentId: UUID,
    ): List<RefundAllocation> =
        transaction.handle
            .createQuery(
                """SELECT ra.* FROM commerce.refund_allocations ra
                   JOIN commerce.payment_allocations a ON a.allocation_id = ra.payment_allocation_id
                   WHERE a.document_id = :id ORDER BY ra.refund_allocation_id""",
            ).bind("id", documentId)
            .map { rows, _ -> refundAllocation(rows) }
            .list()

    /**
     * Reconciles [payment]'s complete persisted history plus the proposed facts. The runtime
     * does not persist allocation reversals, so none are supplied.
     */
    private fun reconcile(
        transaction: Transaction,
        payment: PaymentRecord,
        additionalAllocations: List<PaymentAllocation> = emptyList(),
        additionalRefunds: List<RefundRecord> = emptyList(),
        additionalRefundAllocations: List<RefundAllocation> = emptyList(),
    ): PaymentReconciliation =
        PaymentReconciliation.reconcile(
            payment,
            allocationsForPayment(transaction, payment.id) + additionalAllocations,
            emptyList(),
            refundsForPayment(transaction, payment.id) + additionalRefunds,
            refundAllocationsForPayment(transaction, payment.id) + additionalRefundAllocations,
        )

    private fun payment(rows: ResultSet): PaymentRecord {
        val provider = rows.getString("external_provider")
        return PaymentRecord(
            rows.getObject("payment_id", UUID::class.java),
            Money(rows.getBigDecimal("amount"), Currency.getInstance(rows.getString("currency").trim())),
            PaymentMethod.valueOf(rows.getString("method")),
            Instant.ofEpochSecond(rows.getLong("received_at_seconds"), rows.getInt("received_at_nanos").toLong()),
            provider?.let { ExternalPaymentReference(it, rows.getString("external_reference")) },
        )
    }

    private fun allocation(rows: ResultSet): PaymentAllocation =
        PaymentAllocation.restore(
            rows.getObject("allocation_id", UUID::class.java),
            rows.getObject("payment_id", UUID::class.java),
            FinancialDocumentReference(
                rows.getObject("document_id", UUID::class.java),
                Version.of(rows.getInt("document_version")),
            ),
            Money(rows.getBigDecimal("amount"), Currency.getInstance(rows.getString("currency").trim())),
            Instant.ofEpochSecond(rows.getLong("allocated_at_seconds"), rows.getInt("allocated_at_nanos").toLong()),
        )

    private fun refund(rows: ResultSet): RefundRecord {
        val provider = rows.getString("external_provider")
        return RefundRecord.restore(
            rows.getObject("refund_id", UUID::class.java),
            rows.getObject("payment_id", UUID::class.java),
            Money(rows.getBigDecimal("amount"), Currency.getInstance(rows.getString("currency").trim())),
            PaymentMethod.valueOf(rows.getString("method")),
            Instant.ofEpochSecond(rows.getLong("refunded_at_seconds"), rows.getInt("refunded_at_nanos").toLong()),
            provider?.let { ExternalRefundReference(it, rows.getString("external_reference")) },
        )
    }

    private fun refundAllocation(rows: ResultSet): RefundAllocation =
        RefundAllocation.restore(
            rows.getObject("refund_allocation_id", UUID::class.java),
            rows.getObject("refund_id", UUID::class.java),
            rows.getObject("payment_allocation_id", UUID::class.java),
            Money(rows.getBigDecimal("amount"), Currency.getInstance(rows.getString("currency").trim())),
            Instant.ofEpochSecond(rows.getLong("allocated_at_seconds"), rows.getInt("allocated_at_nanos").toLong()),
        )
}
