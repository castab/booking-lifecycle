@file:JvmName("FinancialDocumentHistories")

package io.github.castab.commerce.financial

import java.util.UUID

/**
 * Application-supplied access to stored [FinancialDocument] snapshots.
 *
 * This is a persistence-agnostic service provider interface. The library never loads
 * history on its own and does not know where snapshots live: an implementation may use a
 * relational table, a document store, a key-value store, a remote API, or an in-memory
 * collection. A typical store holds one immutable snapshot per row or document, keyed by
 * `(id, version)`, with the stage, previous version, and line items alongside.
 * Implementations rebuild snapshots with the stages' `restore` factories.
 *
 * Each call retrieves at most one snapshot. Nothing here walks a lineage, so a document
 * with thousands of versions costs one lookup per requested version.
 *
 * Writing snapshots, and rejecting a conflicting write of an `(id, version)` that already
 * exists, is the persistence layer's responsibility and is not part of this interface.
 */
interface FinancialDocumentHistory {
    /**
     * Returns the snapshot identified by [reference], or `null` if it is not stored.
     *
     * A returned snapshot must have exactly the referenced id and version.
     */
    fun retrieveVersion(reference: FinancialDocumentReference): FinancialDocument?

    /**
     * Returns the stored snapshot of lineage [id] with the highest version, or `null` if no
     * snapshot of that lineage is stored.
     */
    fun retrieveLatestVersion(id: UUID): FinancialDocument?
}

/**
 * Retrieves the snapshot this document was derived from.
 *
 * Returns `null` without any lookup when this is the first snapshot of its lineage.
 * Otherwise performs exactly one [FinancialDocumentHistory.retrieveVersion] call for
 * [FinancialDocument.previousReference], and nothing else: earlier versions are not
 * retrieved.
 *
 * @throws IllegalStateException if [from] returns a snapshot other than the one requested.
 */
fun FinancialDocument.retrievePreviousVersion(from: FinancialDocumentHistory): FinancialDocument? {
    val previous = previousReference ?: return null
    return from.retrieveVersion(previous).checkMatches(previous)
}

/**
 * Retrieves the snapshot of this document's lineage at [version], with one
 * [FinancialDocumentHistory.retrieveVersion] call and without retrieving any intervening
 * version.
 *
 * @throws IllegalStateException if [from] returns a snapshot other than the one requested.
 */
fun FinancialDocument.retrieveVersion(
    version: Version,
    from: FinancialDocumentHistory,
): FinancialDocument? {
    val reference = FinancialDocumentReference(id, version)
    return from.retrieveVersion(reference).checkMatches(reference)
}

/**
 * Retrieves the latest stored snapshot of this document's lineage, which may be this
 * snapshot or a newer one, by delegating to [FinancialDocumentHistory.retrieveLatestVersion]
 * with this document's id.
 *
 * @throws IllegalStateException if [from] returns a snapshot of another lineage.
 */
fun FinancialDocument.retrieveLatestVersion(from: FinancialDocumentHistory): FinancialDocument? {
    val latest = from.retrieveLatestVersion(id)
    check(latest == null || latest.id == id) {
        "History returned financial document ${latest?.id} when asked for the latest version of $id"
    }
    return latest
}

private fun FinancialDocument?.checkMatches(reference: FinancialDocumentReference): FinancialDocument? {
    check(this == null || this.reference == reference) {
        "History returned ${this?.reference} when asked for $reference"
    }
    return this
}
