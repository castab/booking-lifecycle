package io.github.castab.commerce.runtime.financial

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.ExternalRefundReference
import io.github.castab.commerce.payment.FinancialDocumentReconciliation
import io.github.castab.commerce.payment.PaymentAllocation
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentReconciliation
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.payment.RefundAllocation
import io.github.castab.commerce.payment.RefundRecord
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.runtime.persistence.DepositRequirementRepository
import io.github.castab.commerce.runtime.persistence.PostgresFinancialDocumentRepository
import io.github.castab.commerce.runtime.persistence.PostgresPaymentRepository
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import java.time.Instant
import java.util.Collections
import java.util.UUID

/**
 * Financial ledger operations. Convenience methods open a transaction; their [Transaction]
 * overloads join the caller's transaction, including application-owned writes. Documents
 * and payment facts (payments, allocations, refunds, and refund allocations) are
 * append-only; settlement is derived on read. Every operation that consumes a payment's
 * value locks that payment's row first, so allocations and refunds of one payment are
 * serialized and each is checked against the other's committed facts.
 */
class FinancialLedger internal constructor(
    private val transactor: Transactor,
    private val documents: PostgresFinancialDocumentRepository,
    private val payments: PostgresPaymentRepository,
) {
    private val requirements = DepositRequirementRepository(documents)

    /**
     * Approves, replaces, or reactivates a deposit by appending Active. Both expected tokens
     * must match; null expects no requirement history. Approval freezes the amount against
     * exactly [expectedDocumentVersion], without a stage policy or workflow side effects.
     * Stale tokens fail with Conflict; missing lineages with NotFound; invalid terms with
     * ValidationFailed. Hosts conventionally enforce DepositRequirementManage on mutations.
     */
    fun activateDepositRequirement(
        documentId: UUID,
        expectedDocumentVersion: Version,
        terms: DepositTerms,
        expectedRequirementRevision: DepositRequirementRevision?,
    ): DepositRequirementVersion =
        transactor.inTransaction {
            activateDepositRequirement(it, documentId, expectedDocumentVersion, terms, expectedRequirementRevision)
        }

    /**
     * Appends approved terms in the caller's transaction, keeping the lineage lock until it
     * ends. Changes after a REPEATABLE_READ snapshot began fail as Conflict; retry the whole
     * caller transaction. This overload neither opens a transaction nor changes isolation.
     */
    fun activateDepositRequirement(
        transaction: Transaction,
        documentId: UUID,
        expectedDocumentVersion: Version,
        terms: DepositTerms,
        expectedRequirementRevision: DepositRequirementRevision?,
    ): DepositRequirementVersion {
        documents.lockLineage(transaction, documentId)
        val document = latest(transaction, documentId)
        if (document.version !=
            expectedDocumentVersion
        ) {
            throw CommerceFailure.Conflict("Financial document $documentId has a stale expected version")
        }
        val current = requirements.latest(transaction, documentId)?.requirement
        if (current?.revision !=
            expectedRequirementRevision
        ) {
            throw CommerceFailure.Conflict("Deposit requirement for $documentId has a stale expected revision")
        }
        val next =
            validating {
                current?.activate(document, terms) ?: DepositRequirement.Active.create(document, terms)
            }
        return requirements.insert(transaction, next)
    }

    /** Withdraws current approved terms; document-version changes do not prevent withdrawal. */
    fun withdrawDepositRequirement(
        documentId: UUID,
        expectedRequirementRevision: DepositRequirementRevision,
    ): DepositRequirementVersion = transactor.inTransaction { withdrawDepositRequirement(it, documentId, expectedRequirementRevision) }

    /**
     * Withdraws in the caller's transaction. Missing history fails with NotFound, a stale token
     * with Conflict, and an already withdrawn requirement with IllegalTransition.
     */
    fun withdrawDepositRequirement(
        transaction: Transaction,
        documentId: UUID,
        expectedRequirementRevision: DepositRequirementRevision,
    ): DepositRequirementVersion {
        documents.lockLineage(transaction, documentId)
        val current =
            requirements.latest(transaction, documentId)?.requirement
                ?: throw CommerceFailure.NotFound("Deposit requirement for $documentId was not found")
        if (current.revision !=
            expectedRequirementRevision
        ) {
            throw CommerceFailure.Conflict("Deposit requirement for $documentId has a stale expected revision")
        }
        if (current !is DepositRequirement.Active) {
            throw CommerceFailure.IllegalTransition(
                "Deposit requirement for $documentId is already withdrawn",
            )
        }
        return requirements.insert(transaction, current.withdraw())
    }

    /**
     * Latest persisted requirement, including withdrawal, or null if never configured.
     * Uses REPEATABLE_READ with no mutation locks. Missing lineage fails with NotFound.
     * Inside an outer transaction use the Transaction overload: nesting cannot change its
     * isolation. Hosts conventionally enforce FinancialDocumentRead on requirement reads.
     */
    fun latestDepositRequirement(documentId: UUID): DepositRequirementVersion? =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { latestDepositRequirement(it, documentId) }

    /** Reads through the caller's transaction without changing isolation; choose REPEATABLE_READ for coherence. */
    fun latestDepositRequirement(
        transaction: Transaction,
        documentId: UUID,
    ): DepositRequirementVersion? {
        latest(transaction, documentId)
        return requirements.latest(transaction, documentId)
    }

    /** Complete immutable requirement history, oldest first; empty means never configured. Uses REPEATABLE_READ. */
    fun depositRequirementHistory(documentId: UUID): List<DepositRequirementVersion> =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { depositRequirementHistory(it, documentId) }

    /** Reads history in the caller's transaction; missing lineage fails with NotFound. */
    fun depositRequirementHistory(
        transaction: Transaction,
        documentId: UUID,
    ): List<DepositRequirementVersion> {
        latest(transaction, documentId)
        return Collections.unmodifiableList(requirements.history(transaction, documentId))
    }

    /**
     * Current financial views of explicit lineages, in input iteration order. Empty input
     * returns empty output, duplicate ids fail ValidationFailed, and any missing lineage
     * fails NotFound. Four set-based queries read documents, allocations, unwinds, and
     * requirements; no row locks, payment receipt times, or application data participate.
     * The entire read uses one REPEATABLE_READ transaction. Inside an outer transaction
     * use the Transaction overload and select its isolation at the outer boundary.
     */
    fun financialLineages(documentIds: Collection<UUID>): List<FinancialLineageView> =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { financialLineages(it, documentIds) }

    /**
     * Reads entirely through the caller's transaction, including its uncommitted facts.
     * Does not change isolation or open a transaction. Multiple queries are coherent under
     * concurrent writes when the caller chooses REPEATABLE_READ; READ_COMMITTED may combine
     * committed snapshots and cause reconciliation to reject inconsistent facts.
     */
    fun financialLineages(
        transaction: Transaction,
        documentIds: Collection<UUID>,
    ): List<FinancialLineageView> {
        val ids = documentIds.toList()
        if (ids.isEmpty()) return emptyList()
        if (ids.toSet().size != ids.size) throw CommerceFailure.ValidationFailed("Financial lineage ids must not repeat")
        val latest = documents.latestVersions(transaction, ids)
        ids.firstOrNull { it !in latest }?.let { throw CommerceFailure.NotFound("Financial document $it was not found") }
        val allocations = payments.allocationsForLineages(transaction, ids)
        val byLineage = allocations.groupBy { it.financialDocumentReference.id }
        val allocationLineages = allocations.associate { it.id to it.financialDocumentReference.id }
        val unwinds =
            payments
                .refundAllocationsForLineages(
                    transaction,
                    ids,
                ).groupBy { allocationLineages.getValue(it.paymentAllocationReference) }
        val deposits = requirements.latestForLineages(transaction, ids)
        return Collections.unmodifiableList(
            ids.map { id ->
                val version = latest.getValue(id)
                val applied = byLineage[id].orEmpty()
                val refunded = unwinds[id].orEmpty()
                val deposit = deposits[id]
                val reconciliation = FinancialDocumentReconciliation.reconcile(version.document, applied, emptyList(), refunded)
                FinancialLineageView.from(
                    version,
                    reconciliation,
                    deposit,
                    FinancialLineageActivity(
                        version.createdAt,
                        deposit?.createdAt,
                        applied.maxOfOrNull {
                            it.allocatedAt
                        },
                        refunded.maxOfOrNull { it.allocatedAt },
                    ),
                )
            },
        )
    }

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

    /** Reads an exact persisted version with its database-assigned creation instant. */
    fun version(reference: FinancialDocumentReference): FinancialDocumentVersion = transactor.inTransaction { version(it, reference) }

    /** Reads an exact persisted version in the caller's transaction. */
    fun version(
        transaction: Transaction,
        reference: FinancialDocumentReference,
    ): FinancialDocumentVersion =
        documents.version(transaction, reference)
            ?: throw CommerceFailure.NotFound("Financial document $reference was not found")

    /** Reads the latest persisted version with its database-assigned creation instant. */
    fun latestVersion(id: UUID): FinancialDocumentVersion = transactor.inTransaction { latestVersion(it, id) }

    /** Reads the latest version in the caller's transaction. */
    fun latestVersion(
        transaction: Transaction,
        id: UUID,
    ): FinancialDocumentVersion =
        documents.latestVersion(transaction, id)
            ?: throw CommerceFailure.NotFound("Financial document $id was not found")

    /** Reads all persisted versions and creation instants in ascending version order. */
    fun versionHistory(id: UUID): List<FinancialDocumentVersion> = transactor.inTransaction { versionHistory(it, id) }

    /** Reads version history in the caller's transaction. */
    fun versionHistory(
        transaction: Transaction,
        id: UUID,
    ): List<FinancialDocumentVersion> =
        documents.versionHistory(transaction, id).ifEmpty { throw CommerceFailure.NotFound("Financial document $id was not found") }

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

    /**
     * Allocates an existing payment, checking its entire persisted history: allocations,
     * refunds, and refund allocations. Refunded money is never available to allocate.
     */
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
        // The row lock serializes allocations and refunds that read this payment's history.
        val payment =
            payments.lockPayment(transaction, paymentId)
                ?: throw CommerceFailure.NotFound("Payment $paymentId was not found")
        val allocation = validating { PaymentAllocation.create(allocationId, payment, document, amount, allocatedAt) }
        // The repository checks the complete persisted history under this payment lock.
        payments.insertAllocation(transaction, allocation)
        return allocation
    }

    /**
     * Records that money of an existing payment was returned, with the refund allocations
     * that unwind applied value. [allocations] names exactly which payment allocations the
     * refund unwinds and by how much; the rest of the refund came from the payment's
     * unapplied value. The refund and its refund allocations are checked together against
     * the payment's complete history and appended atomically, or not at all.
     */
    @JvmOverloads
    fun recordRefund(
        paymentId: UUID,
        refundId: UUID,
        amount: Money,
        method: PaymentMethod,
        refundedAt: Instant,
        externalReference: ExternalRefundReference? = null,
        allocations: List<RefundAllocationPortion> = emptyList(),
    ): RecordedRefund =
        transactor.inTransaction { transaction ->
            recordRefund(transaction, paymentId, refundId, amount, method, refundedAt, externalReference, allocations)
        }

    /** Records a refund and its refund allocations in the caller's transaction. */
    @JvmOverloads
    fun recordRefund(
        transaction: Transaction,
        paymentId: UUID,
        refundId: UUID,
        amount: Money,
        method: PaymentMethod,
        refundedAt: Instant,
        externalReference: ExternalRefundReference? = null,
        allocations: List<RefundAllocationPortion> = emptyList(),
    ): RecordedRefund {
        // The same row lock as allocatePayment: both consume this payment's finite value.
        val payment =
            payments.lockPayment(transaction, paymentId)
                ?: throw CommerceFailure.NotFound("Payment $paymentId was not found")
        val refund = validating { RefundRecord.create(refundId, payment, amount, method, refundedAt, externalReference) }
        val refundAllocations =
            allocations.map { portion ->
                val allocation =
                    payments.retrieveAllocation(transaction, portion.paymentAllocationId)
                        ?: throw CommerceFailure.NotFound("Payment allocation ${portion.paymentAllocationId} was not found")
                validating { RefundAllocation.create(portion.id, refund, allocation, portion.amount, portion.allocatedAt) }
            }
        // The repository checks the complete proposed history under this payment lock.
        payments.insertRefund(transaction, refund, refundAllocations)
        return RecordedRefund(refund, refundAllocations)
    }

    /** Reconciles a payment against its complete persisted history. */
    fun reconcilePayment(paymentId: UUID): PaymentReconciliation =
        transactor.inTransaction { transaction -> reconcilePayment(transaction, paymentId) }

    /**
     * Reconciles a payment in the caller's transaction. Locks the payment row, like the
     * operations that append its facts, so the allocations, refunds, and refund allocations
     * it reads are one consistent history.
     */
    fun reconcilePayment(
        transaction: Transaction,
        paymentId: UUID,
    ): PaymentReconciliation {
        val payment =
            payments.lockPayment(transaction, paymentId)
                ?: throw CommerceFailure.NotFound("Payment $paymentId was not found")
        // The runtime does not persist allocation reversals.
        return PaymentReconciliation.reconcile(
            payment,
            payments.allocationsForPayment(transaction, paymentId),
            emptyList(),
            payments.refundsForPayment(transaction, paymentId),
            payments.refundAllocationsForPayment(transaction, paymentId),
        )
    }

    /**
     * Reads the complete persisted history of one payment: the payment, all its allocations,
     * refunds, and refund allocations, and the reconciliation derived from exactly those
     * facts. This is how an application rediscovers payment, allocation, and refund ids after
     * the response of the mutation that created them is gone.
     *
     * Runs in one `REPEATABLE_READ` transaction, so the facts and the reconciliation are one
     * coherent committed state, and it takes no row lock: it never blocks or delays the
     * operations that append facts. See [PaymentHistory] for the ordering of its lists.
     *
     * This overload owns its transaction and requests `REPEATABLE_READ`. When already inside an
     * application-owned transaction, call the overload that accepts that [Transaction] instead:
     * on the same thread this call would join the open transaction, which keeps its own
     * isolation (`READ_COMMITTED` by default) or is rejected if that differs. It is never a way
     * to change the isolation of an outer transaction.
     *
     * @throws CommerceFailure.NotFound if there is no payment [paymentId].
     */
    fun paymentHistory(paymentId: UUID): PaymentHistory =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction -> paymentHistory(transaction, paymentId) }

    /**
     * Reads a payment's history in the caller's transaction, seeing the caller's own
     * uncommitted writes. It reads the payment's facts with several queries and takes no
     * lock, so the result is coherent only if the caller's transaction is
     * [TransactionIsolation.REPEATABLE_READ] (or the payment has no concurrent writers). The
     * returned [PaymentHistory] always reconciles exactly the facts it exposes; the isolation
     * decides whether those facts come from one database snapshot. At `READ_COMMITTED`, a
     * concurrent commit between queries can make the returned facts combine different
     * committed database states, potentially producing a history that never existed as one
     * snapshot or causing reconciliation to reject the mixed facts. This transaction overload
     * does not change the caller's isolation; the convenience overload avoids the risk by
     * owning a `REPEATABLE_READ` transaction. Use
     * [reconcilePayment], which locks the payment row, when the history is about to be acted on.
     *
     * @throws CommerceFailure.NotFound if there is no payment [paymentId].
     */
    fun paymentHistory(
        transaction: Transaction,
        paymentId: UUID,
    ): PaymentHistory {
        val payment =
            payments.retrievePayment(transaction, paymentId)
                ?: throw CommerceFailure.NotFound("Payment $paymentId was not found")
        return historyOf(transaction, payment)
    }

    /**
     * Reads the history of every payment that has ever been allocated to any version of the
     * financial-document lineage [documentId], ordered ascending by
     * [PaymentRecord.receivedAt], then [PaymentRecord.id].
     *
     * Discovery is historical, not a filter on current value: a payment stays in the result
     * after refunds have unwound its allocations to this document completely. Each element is
     * the payment's complete [PaymentHistory], including its allocations to other documents;
     * its reconciliation is never computed from this lineage's allocations alone, because
     * that would misstate what remains allocatable or refundable. Select the allocations
     * whose `financialDocumentReference.id` is [documentId] to show only this document's.
     * A payment that was never allocated cannot be discovered here.
     *
     * Runs in one `REPEATABLE_READ` transaction: every history in the result belongs to the
     * same committed state, and no row lock is taken. Like [paymentHistory], this overload owns
     * its transaction; inside an application-owned transaction, call the overload that accepts
     * that [Transaction] instead, and open the outer transaction at `REPEATABLE_READ` for a
     * coherent result.
     *
     * @throws CommerceFailure.NotFound if the document lineage does not exist. A lineage
     * that exists and has no payments yields an empty list.
     */
    fun paymentHistoriesForLineage(documentId: UUID): List<PaymentHistory> =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            paymentHistoriesForLineage(transaction, documentId)
        }

    /**
     * Reads the payment histories of a document lineage in the caller's transaction. Ordering,
     * discovery, `NotFound` behavior, and the isolation the caller's transaction needs for a
     * coherent result are those of [paymentHistoriesForLineage] and [paymentHistory].
     */
    fun paymentHistoriesForLineage(
        transaction: Transaction,
        documentId: UUID,
    ): List<PaymentHistory> {
        latest(transaction, documentId)
        return payments
            .allocationsForLineage(transaction, documentId)
            .mapTo(LinkedHashSet()) { it.paymentReference }
            .map { paymentId ->
                checkNotNull(payments.retrievePayment(transaction, paymentId)) {
                    "Payment $paymentId has allocations but no payment record"
                }
            }.sortedWith(compareBy<PaymentRecord> { it.receivedAt }.thenBy { it.id })
            .map { historyOf(transaction, it) }
    }

    /**
     * Discovers every payment with kept funds not currently applied to a document, in
     * [PaymentRecord.receivedAt] then [PaymentRecord.id] order. The derived
     * [PaymentHistory.reconciliation]'s `unallocated` amount is the available value:
     * payment amount minus refunds and effective allocations. Fully and partly unapplied
     * payments are included; a fully allocated or fully refunded payment is excluded.
     * The complete history and original payment metadata accompany each result.
     *
     * This convenience read uses one `REPEATABLE_READ` snapshot without row locks. Inside
     * an application-owned transaction, use the [Transaction] overload and choose
     * `REPEATABLE_READ` at its outer boundary for a coherent concurrent read.
     */
    fun unappliedPayments(): List<PaymentHistory> = transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { unappliedPayments(it) }

    /** Discovers payments with unapplied value in the caller's transaction. */
    fun unappliedPayments(transaction: Transaction): List<PaymentHistory> =
        payments
            .payments(transaction)
            .sortedWith(compareBy<PaymentRecord> { it.receivedAt }.thenBy { it.id })
            .map { historyOf(transaction, it) }
            .filter {
                it.reconciliation.unallocated.amount
                    .signum() > 0
            }

    private fun historyOf(
        transaction: Transaction,
        payment: PaymentRecord,
    ): PaymentHistory =
        PaymentHistory.from(
            payment,
            payments.allocationsForPayment(transaction, payment.id),
            payments.refundsForPayment(transaction, payment.id),
            payments.refundAllocationsForPayment(transaction, payment.id),
        )

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
            // Refund allocations unwind applied value; the runtime does not persist allocation reversals.
            FinancialDocumentReconciliation.reconcile(
                document,
                payments.allocationsForLineage(transaction, document.id),
                emptyList(),
                payments.refundAllocationsForLineage(transaction, document.id),
            )
        }
}
