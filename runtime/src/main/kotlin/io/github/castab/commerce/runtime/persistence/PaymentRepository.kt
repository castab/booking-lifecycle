package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.ExternalPaymentReference
import io.github.castab.commerce.payment.PaymentAllocation
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentReconciliation
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.runtime.operation.CommerceFailure
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.sql.ResultSet
import java.time.Instant
import java.util.Currency
import java.util.UUID

/** Immutable payment and allocation facts in caller-owned transactions. */
interface PaymentRepository {
    fun insertPayment(
        transaction: Transaction,
        payment: PaymentRecord,
    )

    fun retrievePayment(
        transaction: Transaction,
        id: UUID,
    ): PaymentRecord?

    /** Serializes allocation attempts for one payment until the caller commits. */
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
            PaymentReconciliation.reconcile(payment, allocationsForPayment(transaction, payment.id) + allocation)
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
}
