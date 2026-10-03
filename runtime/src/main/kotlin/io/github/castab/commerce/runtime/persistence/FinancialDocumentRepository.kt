package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentHistory
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.FinancialDocumentVersion
import io.github.castab.commerce.runtime.operation.CommerceFailure
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** Append-only financial snapshots in a caller-owned transaction. */
interface FinancialDocumentRepository {
    fun insert(
        transaction: Transaction,
        snapshot: FinancialDocument,
    )

    fun retrieveVersion(
        transaction: Transaction,
        reference: FinancialDocumentReference,
    ): FinancialDocument?

    fun retrieveLatestVersion(
        transaction: Transaction,
        id: UUID,
    ): FinancialDocument?

    fun history(
        transaction: Transaction,
        id: UUID,
    ): List<FinancialDocument>

    /** Adapts transaction-bound reads to the domain history SPI. */
    fun asHistory(transaction: Transaction): FinancialDocumentHistory =
        object : FinancialDocumentHistory {
            override fun retrieveVersion(reference: FinancialDocumentReference): FinancialDocument? =
                this@FinancialDocumentRepository.retrieveVersion(transaction, reference)

            override fun retrieveLatestVersion(id: UUID): FinancialDocument? =
                this@FinancialDocumentRepository.retrieveLatestVersion(transaction, id)
        }
}

internal class PostgresFinancialDocumentRepository : FinancialDocumentRepository {
    override fun insert(
        transaction: Transaction,
        snapshot: FinancialDocument,
    ) {
        // Document successors and deposit approval share one lineage lock, without locking
        // payment rows or the snapshot rows that payment foreign keys reference.
        if (snapshot.previousVersion != null) {
            val current =
                try {
                    lockLineage(transaction, snapshot.id)
                } catch (_: CommerceFailure.NotFound) {
                    throw CommerceFailure.Conflict("Financial document ${snapshot.reference} has no stored predecessor")
                }
            if (current != snapshot.previousVersion) {
                throw CommerceFailure.Conflict("Financial document ${snapshot.reference} has a stale predecessor")
            }
        }
        val predecessor = snapshot.previousReference?.let { retrieveVersion(transaction, it) }
        if (snapshot.previousReference != null && predecessor == null) {
            throw CommerceFailure.Conflict("Financial document ${snapshot.reference} has no stored predecessor")
        }
        if (predecessor != null && !predecessor.mayAdvanceTo(snapshot)) {
            throw CommerceFailure.Conflict("Financial document ${snapshot.reference} has an invalid stage successor")
        }
        try {
            // The snapshot and its ordered lines are one immutable fact: one row, one insert.
            transaction.handle
                .createUpdate(
                    """INSERT INTO commerce.financial_document_snapshots
                       (document_id, version, previous_version, stage, lines)
                       VALUES (:id, :version, :previous, :stage, CAST(:lines AS jsonb))""",
                ).bind("id", snapshot.id)
                .bind("version", snapshot.version.number)
                .bind("previous", snapshot.previousVersion?.number)
                .bind("stage", snapshot.stageName())
                .bind("lines", snapshot.lineItems.toStoredLines())
                .execute()
        } catch (e: UnableToExecuteStatementException) {
            if (e.isLockUnavailable() || e.isSerializationFailure()) {
                throw CommerceFailure.Conflict("Financial document ${snapshot.id} has a competing mutation", e)
            }
            if (e.isUniqueViolation()) {
                throw CommerceFailure.Conflict("Financial document ${snapshot.reference} already has this snapshot or successor", e)
            }
            throw e
        }
    }

    override fun retrieveVersion(
        transaction: Transaction,
        reference: FinancialDocumentReference,
    ): FinancialDocument? = version(transaction, reference)?.document

    override fun retrieveLatestVersion(
        transaction: Transaction,
        id: UUID,
    ): FinancialDocument? = latestVersion(transaction, id)?.document

    override fun history(
        transaction: Transaction,
        id: UUID,
    ): List<FinancialDocument> = versionHistory(transaction, id).map { it.document }

    /** Restores the document and its database timestamp together from the one row of that version. */
    fun version(
        transaction: Transaction,
        reference: FinancialDocumentReference,
    ): FinancialDocumentVersion? =
        transaction.handle
            .createQuery(
                """SELECT document_id, version, stage, created_at, lines::text AS lines
                   FROM commerce.financial_document_snapshots
                   WHERE document_id = :id AND version = :version""",
            ).bind("id", reference.id)
            .bind("version", reference.version.number)
            .map { rows, _ -> restore(rows) }
            .findOne()
            .orElse(null)

    /** Restores the highest version's row. */
    fun latestVersion(
        transaction: Transaction,
        id: UUID,
    ): FinancialDocumentVersion? =
        transaction.handle
            .createQuery(
                """SELECT document_id, version, stage, created_at, lines::text AS lines
                   FROM commerce.financial_document_snapshots
                   WHERE document_id = :id ORDER BY version DESC LIMIT 1""",
            ).bind("id", id)
            .map { rows, _ -> restore(rows) }
            .findOne()
            .orElse(null)

    /** Restores every version of the lineage, oldest first, each from its own row, in one query. */
    fun versionHistory(
        transaction: Transaction,
        id: UUID,
    ): List<FinancialDocumentVersion> =
        transaction.handle
            .createQuery(
                """SELECT document_id, version, stage, created_at, lines::text AS lines
                   FROM commerce.financial_document_snapshots
                   WHERE document_id = :id ORDER BY version""",
            ).bind("id", id)
            .map { rows, _ -> restore(rows) }
            .list()

    /**
     * Serializes document successors and requirement mutations, including first approval.
     * Never waits: the caller may already hold a payment lock, while the lineage holder
     * waits for that payment. NOWAIT breaks that cycle with Conflict at this operation.
     * Reacquiring our own lock succeeds. The snapshot-insert trigger enforces the same rule.
     */
    fun lockLineage(
        transaction: Transaction,
        id: UUID,
    ): Version {
        try {
            val number =
                transaction.handle
                    .createQuery(
                        """SELECT latest_version FROM commerce.financial_document_lineages
                   WHERE document_id = :id FOR NO KEY UPDATE NOWAIT""",
                    ).bind("id", id)
                    .mapTo(Int::class.java)
                    .findOne()
                    .orElse(null)
                    ?: throw CommerceFailure.NotFound("Financial document $id was not found")
            return Version.of(number)
        } catch (e: UnableToExecuteStatementException) {
            if (e.isLockUnavailable()) {
                throw CommerceFailure.Conflict("Financial document $id has a competing mutation", e)
            }
            if (e.isSerializationFailure()) {
                throw CommerceFailure.Conflict(
                    "Financial document $id changed after the transaction snapshot",
                    e,
                )
            }
            throw e
        }
    }

    /** Set-based current versions; empty input is handled by the ledger before reaching SQL. */
    fun latestVersions(
        transaction: Transaction,
        ids: Collection<UUID>,
    ): Map<UUID, FinancialDocumentVersion> =
        transaction.handle
            .createQuery(
                """SELECT DISTINCT ON (document_id) document_id, version, stage, created_at, lines::text AS lines
               FROM commerce.financial_document_snapshots WHERE document_id IN (<ids>)
               ORDER BY document_id, version DESC""",
            ).bindList("ids", ids)
            .map { rows, _ -> restore(rows) }
            .list()
            .associateBy { it.document.id }

    private fun restore(rows: ResultSet): FinancialDocumentVersion =
        FinancialDocumentVersion.from(restoreDocument(rows), rows.getObject("created_at", OffsetDateTime::class.java).toInstant())

    /** Strict document restoration for joins that intentionally omit snapshot timestamp metadata. */
    internal fun restoreDocument(rows: ResultSet): FinancialDocument {
        val id = rows.getObject("document_id", UUID::class.java)
        val version = Version.of(rows.getInt("version"))
        val what = "financial document $id version ${version.number}"
        val lines = restoreStoredLines("lines of $what", rows.getString("lines"))
        return try {
            when (val stage = rows.getString("stage")) {
                "ESTIMATE" -> FinancialDocument.Estimate.restore(id, version, lines)
                "QUOTE" -> FinancialDocument.Quote.restore(id, version, lines)
                "INVOICE" -> FinancialDocument.Invoice.restore(id, version, lines)
                else -> error("Unsupported financial document stage: $stage")
            }
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Persisted $what violates a domain invariant: ${e.message}", e)
        }
    }

    private fun FinancialDocument.stageName(): String =
        when (this) {
            is FinancialDocument.Estimate -> "ESTIMATE"
            is FinancialDocument.Quote -> "QUOTE"
            is FinancialDocument.Invoice -> "INVOICE"
        }

    private fun FinancialDocument.mayAdvanceTo(next: FinancialDocument): Boolean =
        when (this) {
            is FinancialDocument.Estimate -> next is FinancialDocument.Estimate || next is FinancialDocument.Quote
            is FinancialDocument.Quote -> next is FinancialDocument.Quote || next is FinancialDocument.Invoice
            is FinancialDocument.Invoice -> next is FinancialDocument.Invoice
        }
}
