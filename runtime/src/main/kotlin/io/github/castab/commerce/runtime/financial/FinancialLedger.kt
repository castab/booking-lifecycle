package io.github.castab.commerce.runtime.financial

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.payment.FinancialDocumentReconciliation
import io.github.castab.commerce.payment.PaymentAllocation
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.runtime.persistence.FinancialDocumentRepository
import io.github.castab.commerce.runtime.persistence.PaymentRepository
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import java.time.Instant
import java.util.UUID

/**
 * Financial ledger operations. Convenience methods open a transaction; their [Transaction]
 * overloads join the caller's transaction, including application-owned writes. Documents
 * and payment facts are append-only; settlement is derived on read.
 */
class FinancialLedger internal constructor(
    private val transactor: Transactor,
    private val documents: FinancialDocumentRepository,
    private val payments: PaymentRepository,
) {
    /** Stores an application-created first snapshot in the caller's transaction. */
    fun create(
        transaction: Transaction,
        document: FinancialDocument,
    ): FinancialDocument {
        if (document.previousVersion != null) {
            throw CommerceFailure.ValidationFailed("A new financial document must be its first snapshot")
        }
        documents.insert(transaction, document)
        return document
    }

    /** Stores an application-created first snapshot in a new transaction. */
    fun create(document: FinancialDocument): FinancialDocument = transactor.inTransaction { create(it, document) }

    /** Retrieves one exact immutable snapshot. */
    fun get(reference: FinancialDocumentReference): FinancialDocument =
        transactor.inTransaction { transaction -> get(transaction, reference) }

    /** Retrieves one exact snapshot within the caller's transaction. */
    fun get(
        transaction: Transaction,
        reference: FinancialDocumentReference,
    ): FinancialDocument =
        documents.retrieveVersion(transaction, reference)
            ?: throw CommerceFailure.NotFound("Financial document $reference was not found")

    /** Retrieves the highest stored version of a lineage. */
    fun latest(id: UUID): FinancialDocument = transactor.inTransaction { transaction -> latest(transaction, id) }

    /** Retrieves the highest stored version within the caller's transaction. */
    fun latest(
        transaction: Transaction,
        id: UUID,
    ): FinancialDocument =
        documents.retrieveLatestVersion(transaction, id)
            ?: throw CommerceFailure.NotFound("Financial document $id was not found")

    /** Retrieves all snapshots of a lineage in ascending version order. */
    fun history(id: UUID): List<FinancialDocument> = transactor.inTransaction { transaction -> history(transaction, id) }

    /** Retrieves ordered history within the caller's transaction. */
    fun history(
        transaction: Transaction,
        id: UUID,
    ): List<FinancialDocument> =
        documents.history(transaction, id).ifEmpty { throw CommerceFailure.NotFound("Financial document $id was not found") }

    /** Applies the domain change order and appends its same-stage successor. */
    fun changeOrder(
        id: UUID,
        changeOrder: ChangeOrder,
    ): FinancialDocument = transactor.inTransaction { transaction -> changeOrder(transaction, id, changeOrder) }

    /** Applies a change order and appends its successor in the caller's transaction. */
    fun changeOrder(
        transaction: Transaction,
        id: UUID,
        changeOrder: ChangeOrder,
    ): FinancialDocument {
        val current = latest(transaction, id)
        val next = validating { current.changeOrder(changeOrder) }
        documents.insert(transaction, next)
        return next
    }

    /** Appends an Estimate's domain-derived Quote successor. */
    fun issueQuote(id: UUID): FinancialDocument.Quote = transactor.inTransaction { transaction -> issueQuote(transaction, id) }

    /** Appends a Quote successor in the caller's transaction. */
    fun issueQuote(
        transaction: Transaction,
        id: UUID,
    ): FinancialDocument.Quote {
        val current = latest(transaction, id)
        if (current !is FinancialDocument.Estimate) {
            throw CommerceFailure.IllegalTransition("Financial document $id is not an estimate")
        }
        return current.toQuote().also { documents.insert(transaction, it) }
    }

    /** Appends a Quote's domain-derived Invoice successor. */
    fun issueInvoice(id: UUID): FinancialDocument.Invoice = transactor.inTransaction { transaction -> issueInvoice(transaction, id) }

    /** Appends an Invoice successor in the caller's transaction. */
    fun issueInvoice(
        transaction: Transaction,
        id: UUID,
    ): FinancialDocument.Invoice {
        val current = latest(transaction, id)
        if (current !is FinancialDocument.Quote) {
            throw CommerceFailure.IllegalTransition("Financial document $id is not a quote")
        }
        return current.toInvoice().also { documents.insert(transaction, it) }
    }

    /** A payment may be recorded before its allocation is known. */
    fun recordPayment(payment: PaymentRecord): PaymentRecord =
        transactor.inTransaction { transaction -> recordPayment(transaction, payment) }

    /** Records an unapplied payment in the caller's transaction. */
    fun recordPayment(
        transaction: Transaction,
        payment: PaymentRecord,
    ): PaymentRecord {
        payments.insertPayment(transaction, payment)
        return payment
    }

    /** Appends a payment and its first allocation atomically. */
    fun recordPaymentAgainstDocument(
        payment: PaymentRecord,
        allocationId: UUID,
        documentReference: FinancialDocumentReference,
        amount: Money,
        allocatedAt: Instant,
    ): PaymentAllocation =
        transactor.inTransaction { transaction ->
            recordPaymentAgainstDocument(transaction, payment, allocationId, documentReference, amount, allocatedAt)
        }

    /** Records a payment and its first allocation in the caller's transaction. */
    fun recordPaymentAgainstDocument(
        transaction: Transaction,
        payment: PaymentRecord,
        allocationId: UUID,
        documentReference: FinancialDocumentReference,
        amount: Money,
        allocatedAt: Instant,
    ): PaymentAllocation {
        get(transaction, documentReference)
        recordPayment(transaction, payment)
        return allocatePayment(transaction, payment.id, allocationId, documentReference, amount, allocatedAt)
    }

    /** Allocates an existing payment, checking its entire persisted allocation history. */
    fun allocatePayment(
        paymentId: UUID,
        allocationId: UUID,
        documentReference: FinancialDocumentReference,
        amount: Money,
        allocatedAt: Instant,
    ): PaymentAllocation =
        transactor.inTransaction { transaction ->
            allocatePayment(transaction, paymentId, allocationId, documentReference, amount, allocatedAt)
        }

    /** Allocates an existing payment in the caller's transaction. */
    fun allocatePayment(
        transaction: Transaction,
        paymentId: UUID,
        allocationId: UUID,
        documentReference: FinancialDocumentReference,
        amount: Money,
        allocatedAt: Instant,
    ): PaymentAllocation {
        val document = get(transaction, documentReference)
        // The row lock serializes readers of the allocation history for this payment.
        val payment =
            payments.lockPayment(transaction, paymentId)
                ?: throw CommerceFailure.NotFound("Payment $paymentId was not found")
        val allocation = validating { PaymentAllocation.create(allocationId, payment, document, amount, allocatedAt) }
        // The repository checks the complete persisted history under this payment lock.
        payments.insertAllocation(transaction, allocation)
        return allocation
    }

    /** Reconciles the latest obligation against all allocations in its lineage. */
    fun reconcileLatest(id: UUID): FinancialDocumentReconciliation =
        transactor.inTransaction { transaction -> reconcileLatest(transaction, id) }

    /** Reconciles the latest obligation in the caller's transaction. */
    fun reconcileLatest(
        transaction: Transaction,
        id: UUID,
    ): FinancialDocumentReconciliation = reconcileDocument(transaction, latest(transaction, id))

    /** Reconciles the selected snapshot; the domain rejects later allocations. */
    fun reconcile(reference: FinancialDocumentReference): FinancialDocumentReconciliation =
        transactor.inTransaction { transaction -> reconcile(transaction, reference) }

    /** Reconciles the selected snapshot in the caller's transaction. */
    fun reconcile(
        transaction: Transaction,
        reference: FinancialDocumentReference,
    ): FinancialDocumentReconciliation = reconcileDocument(transaction, get(transaction, reference))

    private fun reconcileDocument(
        transaction: Transaction,
        document: FinancialDocument,
    ): FinancialDocumentReconciliation =
        validating {
            FinancialDocumentReconciliation.reconcile(document, payments.allocationsForLineage(transaction, document.id))
        }
}
