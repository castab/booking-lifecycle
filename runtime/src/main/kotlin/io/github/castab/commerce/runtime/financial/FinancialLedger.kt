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
 * Financial ledger operations. Every public operation owns one transaction except the
 * explicit [Transaction] overload of [create], which joins an application's other writes.
 * Documents and payment facts are append-only; settlement is derived on read.
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
        transactor.inTransaction { transaction -> document(transaction, reference) }

    /** Retrieves the highest stored version of a lineage. */
    fun latest(id: UUID): FinancialDocument = transactor.inTransaction { transaction -> latest(transaction, id) }

    /** Retrieves all snapshots of a lineage in ascending version order. */
    fun history(id: UUID): List<FinancialDocument> =
        transactor.inTransaction { transaction ->
            documents.history(transaction, id).ifEmpty { throw CommerceFailure.NotFound("Financial document $id was not found") }
        }

    /** Applies the domain change order and appends its same-stage successor. */
    fun changeOrder(
        id: UUID,
        changeOrder: ChangeOrder,
    ): FinancialDocument =
        transactor.inTransaction { transaction ->
            val current = latest(transaction, id)
            val next = validating { current.changeOrder(changeOrder) }
            documents.insert(transaction, next)
            next
        }

    /** Appends an Estimate's domain-derived Quote successor. */
    fun issueQuote(id: UUID): FinancialDocument.Quote =
        transactor.inTransaction { transaction ->
            val current = latest(transaction, id)
            if (current !is FinancialDocument.Estimate) {
                throw CommerceFailure.IllegalTransition("Financial document $id is not an estimate")
            }
            current.toQuote().also { documents.insert(transaction, it) }
        }

    /** Appends a Quote's domain-derived Invoice successor. */
    fun issueInvoice(id: UUID): FinancialDocument.Invoice =
        transactor.inTransaction { transaction ->
            val current = latest(transaction, id)
            if (current !is FinancialDocument.Quote) {
                throw CommerceFailure.IllegalTransition("Financial document $id is not a quote")
            }
            current.toInvoice().also { documents.insert(transaction, it) }
        }

    /** A payment may be recorded before its allocation is known. */
    fun recordPayment(payment: PaymentRecord): PaymentRecord =
        transactor.inTransaction { transaction ->
            payments.insertPayment(transaction, payment)
            payment
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
            document(transaction, documentReference)
            payments.insertPayment(transaction, payment)
            allocate(transaction, payment.id, allocationId, documentReference, amount, allocatedAt)
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
            allocate(transaction, paymentId, allocationId, documentReference, amount, allocatedAt)
        }

    /** Reconciles the latest obligation against all allocations in its lineage. */
    fun reconcileLatest(id: UUID): FinancialDocumentReconciliation =
        transactor.inTransaction { transaction -> reconcile(transaction, latest(transaction, id)) }

    /** Reconciles the selected snapshot; the domain rejects later allocations. */
    fun reconcile(reference: FinancialDocumentReference): FinancialDocumentReconciliation =
        transactor.inTransaction { transaction -> reconcile(transaction, document(transaction, reference)) }

    private fun allocate(
        transaction: Transaction,
        paymentId: UUID,
        allocationId: UUID,
        reference: FinancialDocumentReference,
        amount: Money,
        allocatedAt: Instant,
    ): PaymentAllocation {
        val document = document(transaction, reference)
        // The row lock serializes readers of the allocation history for this payment.
        val payment =
            payments.lockPayment(transaction, paymentId)
                ?: throw CommerceFailure.NotFound("Payment $paymentId was not found")
        val allocation = validating { PaymentAllocation.create(allocationId, payment, document, amount, allocatedAt) }
        // The repository checks the complete persisted history under this payment lock.
        payments.insertAllocation(transaction, allocation)
        return allocation
    }

    private fun reconcile(
        transaction: Transaction,
        document: FinancialDocument,
    ): FinancialDocumentReconciliation =
        validating {
            FinancialDocumentReconciliation.reconcile(document, payments.allocationsForLineage(transaction, document.id))
        }

    private fun document(
        transaction: Transaction,
        reference: FinancialDocumentReference,
    ): FinancialDocument =
        documents.retrieveVersion(transaction, reference)
            ?: throw CommerceFailure.NotFound("Financial document $reference was not found")

    private fun latest(
        transaction: Transaction,
        id: UUID,
    ): FinancialDocument =
        documents.retrieveLatestVersion(transaction, id)
            ?: throw CommerceFailure.NotFound("Financial document $id was not found")
}
