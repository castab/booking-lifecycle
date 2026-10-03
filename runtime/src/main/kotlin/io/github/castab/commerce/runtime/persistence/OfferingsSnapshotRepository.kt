package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.OfferingsSnapshotReference
import io.github.castab.commerce.runtime.operation.CommerceFailure
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.sql.ResultSet
import java.util.Arrays
import java.util.UUID

/**
 * The current snapshot of each offerings catalog, in caller-owned transactions. Only the
 * current revision is kept; earlier revisions are not retained. For every key that was once
 * present and is not now, the last representation is kept with the revision it was last
 * present in, so used keys stay reserved and retired values can be discovered and restored.
 */
interface OfferingsSnapshotRepository {
    /**
     * Makes [snapshot] its catalog's current state. A first revision creates the catalog. A
     * successor replaces the current revision only when that revision is the successor's
     * immediate predecessor; otherwise, or when a first revision's catalog already exists, it
     * fails with [CommerceFailure.Conflict]. Keys present before and absent from the successor
     * become retired at the replaced revision; retired keys present again are no longer retired.
     */
    fun save(
        transaction: Transaction,
        snapshot: OfferingsSnapshot,
    )

    fun retrieveLatestVersion(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
    ): OfferingsSnapshot?

    /** Whether this natural identity is active or retired in this catalog. */
    fun offeringKeyReserved(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        key: OfferingKey,
    ): Boolean

    /** Whether this natural category identity is active or retired in this catalog. */
    fun categoryKeyReserved(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        key: OfferingCategoryKey,
    ): Boolean

    /** The last representations of retired offerings, in key order. */
    fun retrieveRetiredOfferings(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
    ): List<RetiredCatalogValue<Offering>>

    /** The last representations of retired categories, in key order. */
    fun retrieveRetiredCategories(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
    ): List<RetiredCatalogValue<OfferingCategory>>
}

internal class PostgresOfferingsSnapshotRepository : OfferingsSnapshotRepository {
    override fun save(
        transaction: Transaction,
        snapshot: OfferingsSnapshot,
    ) {
        val predecessor = snapshot.previousRevision
        if (predecessor == null) {
            create(transaction, snapshot)
            return
        }
        // The row lock serializes writers of one catalog: a writer that waited reads the
        // committed revision and conflicts instead of replacing it.
        val current =
            stored(transaction, snapshot.catalogId, forUpdate = true)
                ?: throw CommerceFailure.Conflict("Offerings catalog ${snapshot.catalogId.value} does not exist")
        if (current.snapshot.revision != predecessor) {
            throw CommerceFailure.Conflict(
                "Offerings catalog ${snapshot.catalogId.value} is at ${current.snapshot.revision}, not $predecessor",
            )
        }
        val replaced = current.snapshot.revision.number
        val next =
            StoredCatalog(
                snapshot.categories,
                snapshot.offerings,
                retire(current.catalog.retiredCategories, current.snapshot.categories, snapshot.categories, replaced) { it.key },
                retire(current.catalog.retiredOfferings, current.snapshot.offerings, snapshot.offerings, replaced) { it.key },
            )
        transaction.handle
            .createUpdate(
                """UPDATE commerce.offerings_catalogs SET revision = :revision, catalog = CAST(:catalog AS jsonb)
                   WHERE catalog_id = :catalogId""",
            ).bind("catalogId", snapshot.catalogId.value)
            .bind("revision", snapshot.revision.number)
            .bind("catalog", next.toStored())
            .execute()
    }

    private fun create(
        transaction: Transaction,
        snapshot: OfferingsSnapshot,
    ) {
        try {
            transaction.handle
                .createUpdate(
                    """INSERT INTO commerce.offerings_catalogs (catalog_id, revision, catalog)
                       VALUES (:catalogId, :revision, CAST(:catalog AS jsonb))""",
                ).bind("catalogId", snapshot.catalogId.value)
                .bind("revision", snapshot.revision.number)
                .bind("catalog", StoredCatalog(snapshot.categories, snapshot.offerings, emptyList(), emptyList()).toStored())
                .execute()
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) throw CommerceFailure.Conflict("Offerings catalog ${snapshot.catalogId.value} already exists", e)
            throw e
        }
    }

    override fun retrieveLatestVersion(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
    ): OfferingsSnapshot? = stored(transaction, catalogId)?.snapshot

    override fun offeringKeyReserved(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        key: OfferingKey,
    ): Boolean =
        stored(transaction, catalogId)?.let { current ->
            current.snapshot.offering(key) != null || current.catalog.retiredOfferings.any { it.second.key == key }
        } ?: false

    override fun categoryKeyReserved(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        key: OfferingCategoryKey,
    ): Boolean =
        stored(transaction, catalogId)?.let { current ->
            current.snapshot.category(key) != null || current.catalog.retiredCategories.any { it.second.key == key }
        } ?: false

    override fun retrieveRetiredOfferings(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
    ): List<RetiredCatalogValue<Offering>> =
        retiredValues(catalogId, stored(transaction, catalogId)?.catalog?.retiredOfferings.orEmpty()) { it.key.value }

    override fun retrieveRetiredCategories(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
    ): List<RetiredCatalogValue<OfferingCategory>> =
        retiredValues(catalogId, stored(transaction, catalogId)?.catalog?.retiredCategories.orEmpty()) { it.key.value }

    private class Current(
        val snapshot: OfferingsSnapshot,
        val catalog: StoredCatalog,
    )

    private fun stored(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        forUpdate: Boolean = false,
    ): Current? =
        transaction.handle
            .createQuery(
                "SELECT catalog_id, revision, catalog::text AS catalog FROM commerce.offerings_catalogs WHERE catalog_id = :catalogId" +
                    if (forUpdate) " FOR UPDATE" else "",
            ).bind("catalogId", catalogId.value)
            .map { rows, _ -> current(rows) }
            .findOne()
            .orElse(null)

    /**
     * Restores the row strictly: the active contents through the domain's own invariants, and
     * the retired entries as unique keys, absent from the active contents, each last present
     * in an earlier revision. Anything else fails naming the catalog; persistence never repairs it.
     */
    private fun current(rows: ResultSet): Current {
        val catalogId = OfferingsCatalogId(rows.getObject("catalog_id", UUID::class.java))
        val revision = OfferingsRevision.of(rows.getInt("revision"))
        val what = "offerings catalog ${catalogId.value} $revision"
        val catalog = restoreStoredCatalog(what, rows.getString("catalog"))
        try {
            val snapshot =
                OfferingsSnapshot.restore(
                    catalogId,
                    revision,
                    if (revision == OfferingsRevision.INITIAL) null else OfferingsRevision.of(revision.number - 1),
                    catalog.categories,
                    catalog.offerings,
                )
            requireRetired(catalog.retiredCategories, catalog.categories, revision, "category") { it.key.value }
            requireRetired(catalog.retiredOfferings, catalog.offerings, revision, "offering") { it.key.value }
            return Current(snapshot, catalog)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Persisted $what violates a domain invariant: ${e.message}", e)
        }
    }
}

private fun <T> retiredValues(
    catalogId: OfferingsCatalogId,
    retired: List<Pair<Int, T>>,
    keyOf: (T) -> String,
): List<RetiredCatalogValue<T>> =
    retired
        .sortedWith(compareBy(Utf8ByteOrder) { keyOf(it.second) })
        .map { (revision, value) -> RetiredCatalogValue(OfferingsSnapshotReference(catalogId, OfferingsRevision.of(revision)), value) }

private fun <T> requireRetired(
    retired: List<Pair<Int, T>>,
    active: List<T>,
    revision: OfferingsRevision,
    kind: String,
    keyOf: (T) -> String,
) {
    val keys = retired.map { keyOf(it.second) }
    require(keys.distinct().size == keys.size) { "Duplicate retired $kind key" }
    val activeKeys = active.map(keyOf).toSet()
    val both = keys.firstOrNull { it in activeKeys }
    require(both == null) { "Retired $kind $both is also active" }
    val misdated = retired.firstOrNull { it.first !in 1 until revision.number }
    require(misdated == null) { "Retired $kind ${misdated?.second?.let(keyOf)} was last seen at r${misdated?.first}, not before $revision" }
}

/**
 * The retired entries after replacing [before] with [after]: keys present again are no longer
 * retired, and keys present before but absent after are retired at revision [replaced].
 */
private fun <T, K> retire(
    retired: List<Pair<Int, T>>,
    before: List<T>,
    after: List<T>,
    replaced: Int,
    keyOf: (T) -> K,
): List<Pair<Int, T>> {
    val present = after.map(keyOf).toSet()
    return retired.filter { keyOf(it.second) !in present } + before.filter { keyOf(it) !in present }.map { replaced to it }
}

/**
 * Orders strings by their UTF-8 bytes, which is code point order and matches PostgreSQL's
 * `"C"` collation. Kotlin's own String ordering compares UTF-16 code units and differs for
 * supplementary characters.
 */
private object Utf8ByteOrder : Comparator<String> {
    override fun compare(
        a: String,
        b: String,
    ): Int = Arrays.compareUnsigned(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
