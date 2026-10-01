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

/** Append-only commerce-owned catalog snapshots in caller-owned transactions. */
interface OfferingsSnapshotRepository {
    fun insert(
        transaction: Transaction,
        snapshot: OfferingsSnapshot,
    )

    fun retrieveVersion(
        transaction: Transaction,
        reference: OfferingsSnapshotReference,
    ): OfferingsSnapshot?

    fun retrieveLatestVersion(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
    ): OfferingsSnapshot?

    /** Whether this natural identity has appeared in any revision of this catalog. */
    fun offeringKeyExistsInHistory(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        key: OfferingKey,
    ): Boolean

    /** Whether this natural category identity has appeared in any revision of this catalog. */
    fun categoryKeyExistsInHistory(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        key: OfferingCategoryKey,
    ): Boolean

    /** Last representations absent at [reference], in key order. History is bounded by that immutable revision. */
    fun retrieveRetiredOfferings(
        transaction: Transaction,
        reference: OfferingsSnapshotReference,
    ): List<HistoricalCatalogValue<Offering>>

    /** Last category representations absent at [reference], in key order, bounded by that immutable revision. */
    fun retrieveRetiredCategories(
        transaction: Transaction,
        reference: OfferingsSnapshotReference,
    ): List<HistoricalCatalogValue<OfferingCategory>>
}

internal class PostgresOfferingsSnapshotRepository : OfferingsSnapshotRepository {
    override fun insert(
        transaction: Transaction,
        snapshot: OfferingsSnapshot,
    ) {
        try {
            // The revision and everything it contains are one immutable fact: one row, one insert.
            transaction.handle
                .createUpdate(
                    """INSERT INTO commerce.offerings_snapshots (catalog_id, revision, previous_revision, catalog)
                       VALUES (:catalogId, :revision, :previousRevision, CAST(:catalog AS jsonb))""",
                ).bind("catalogId", snapshot.catalogId.value)
                .bind("revision", snapshot.revision.number)
                .bind("previousRevision", snapshot.previousRevision?.number)
                .bind("catalog", toStoredCatalog(snapshot.categories, snapshot.offerings))
                .execute()
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) throw CommerceFailure.Conflict("Offerings snapshot ${snapshot.reference} already exists", e)
            throw e
        }
    }

    override fun retrieveVersion(
        transaction: Transaction,
        reference: OfferingsSnapshotReference,
    ): OfferingsSnapshot? =
        transaction.handle
            .createQuery(
                """SELECT catalog_id, revision, previous_revision, catalog::text AS catalog
                   FROM commerce.offerings_snapshots WHERE catalog_id = :catalogId AND revision = :revision""",
            ).bind("catalogId", reference.catalogId.value)
            .bind("revision", reference.revision.number)
            .map { rows, _ -> snapshot(rows) }
            .findOne()
            .orElse(null)

    override fun retrieveLatestVersion(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
    ): OfferingsSnapshot? =
        transaction.handle
            .createQuery(
                """SELECT catalog_id, revision, previous_revision, catalog::text AS catalog
                   FROM commerce.offerings_snapshots WHERE catalog_id = :catalogId ORDER BY revision DESC LIMIT 1""",
            ).bind("catalogId", catalogId.value)
            .map { rows, _ -> snapshot(rows) }
            .findOne()
            .orElse(null)

    // History questions reason about immutable revisions, so they work only from complete,
    // strictly restored snapshots: every revision they consult goes through the same
    // restoration as retrieveVersion, and a corrupt revision fails the question rather than
    // being skipped or half-read. Catalog revisions are few, and one catalog's are bounded
    // by the primary key (catalog_id, revision).
    override fun offeringKeyExistsInHistory(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        key: OfferingKey,
    ): Boolean = snapshots(transaction, catalogId, through = null).any { it.offering(key) != null }

    override fun categoryKeyExistsInHistory(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        key: OfferingCategoryKey,
    ): Boolean = snapshots(transaction, catalogId, through = null).any { it.category(key) != null }

    override fun retrieveRetiredOfferings(
        transaction: Transaction,
        reference: OfferingsSnapshotReference,
    ): List<HistoricalCatalogValue<Offering>> =
        retired(
            snapshots(transaction, reference.catalogId, through = reference.revision),
            reference,
            elements = { it.offerings },
            keyOf = { it.key.value },
        )

    override fun retrieveRetiredCategories(
        transaction: Transaction,
        reference: OfferingsSnapshotReference,
    ): List<HistoricalCatalogValue<OfferingCategory>> =
        retired(
            snapshots(transaction, reference.catalogId, through = reference.revision),
            reference,
            elements = { it.categories },
            keyOf = { it.key.value },
        )

    /**
     * Every stored revision of the catalog, oldest first and restored, up to and including
     * [through] when given. Asking about a revision that is not stored is permitted: the
     * revisions that exist up to it are returned.
     */
    private fun snapshots(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        through: OfferingsRevision?,
    ): List<OfferingsSnapshot> =
        transaction.handle
            .createQuery(
                """SELECT catalog_id, revision, previous_revision, catalog::text AS catalog
                   FROM commerce.offerings_snapshots
                   WHERE catalog_id = :catalogId AND (CAST(:through AS integer) IS NULL OR revision <= :through)
                   ORDER BY revision""",
            ).bind("catalogId", catalogId.value)
            .bind("through", through?.number)
            .map { rows, _ -> snapshot(rows) }
            .list()

    /**
     * The last representation of every key that appears in a revision up to [reference] but
     * not in the snapshot at [reference], with the revision it came from, in key byte order.
     * Presence is defined by the restored snapshot at [reference]; when that revision is not
     * stored, nothing is present and every historical key is retired.
     */
    private fun <T> retired(
        history: List<OfferingsSnapshot>,
        reference: OfferingsSnapshotReference,
        elements: (OfferingsSnapshot) -> List<T>,
        keyOf: (T) -> String,
    ): List<HistoricalCatalogValue<T>> {
        val current =
            history
                .lastOrNull { it.revision == reference.revision }
                ?.let(elements)
                .orEmpty()
                .map(keyOf)
                .toSet()
        val lastSeen = HashMap<String, HistoricalCatalogValue<T>>()
        history.forEach { snapshot ->
            elements(snapshot).forEach { element ->
                lastSeen[keyOf(element)] = HistoricalCatalogValue(snapshot.reference, element)
            }
        }
        return lastSeen
            .filterKeys { it !in current }
            .entries
            .sortedWith(compareBy(Utf8ByteOrder) { it.key })
            .map { it.value }
    }

    private fun snapshot(rows: ResultSet): OfferingsSnapshot {
        val catalogId = OfferingsCatalogId(rows.getObject("catalog_id", UUID::class.java))
        val revision = OfferingsRevision.of(rows.getInt("revision"))
        val what = "offerings catalog ${catalogId.value} $revision"
        val (categories, offerings) = restoreStoredCatalog(what, rows.getString("catalog"))
        try {
            return OfferingsSnapshot.restore(
                catalogId,
                revision,
                rows.getInt("previous_revision").takeUnless { rows.wasNull() }?.let(OfferingsRevision::of),
                categories,
                offerings,
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Persisted $what violates a domain invariant: ${e.message}", e)
        }
    }
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
