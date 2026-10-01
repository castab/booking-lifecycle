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

    // History questions are answered from the immutable revisions of one catalog, which the
    // primary key (catalog_id, revision) already bounds. Containment of {"key": ...} in the
    // `offerings` or `categories` array compares the key exactly and needs no index of its own.
    override fun offeringKeyExistsInHistory(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        key: OfferingKey,
    ): Boolean = keyExistsInHistory(transaction, catalogId, "offerings", key.value)

    override fun categoryKeyExistsInHistory(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        key: OfferingCategoryKey,
    ): Boolean = keyExistsInHistory(transaction, catalogId, "categories", key.value)

    override fun retrieveRetiredOfferings(
        transaction: Transaction,
        reference: OfferingsSnapshotReference,
    ): List<HistoricalCatalogValue<Offering>> =
        retired(transaction, reference, "offerings").map { (revision, json) ->
            HistoricalCatalogValue(
                OfferingsSnapshotReference(reference.catalogId, revision),
                restoreStoredOffering("offering in catalog ${reference.catalogId.value} $revision", json),
            )
        }

    override fun retrieveRetiredCategories(
        transaction: Transaction,
        reference: OfferingsSnapshotReference,
    ): List<HistoricalCatalogValue<OfferingCategory>> =
        retired(transaction, reference, "categories").map { (revision, json) ->
            HistoricalCatalogValue(
                OfferingsSnapshotReference(reference.catalogId, revision),
                restoreStoredCategory("category in catalog ${reference.catalogId.value} $revision", json),
            )
        }

    private fun keyExistsInHistory(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        array: String,
        key: String,
    ): Boolean {
        require(array == "offerings" || array == "categories") { "Unsupported catalog array: $array" }
        return transaction.handle
            .createQuery(
                """SELECT EXISTS (
                       SELECT 1 FROM commerce.offerings_snapshots
                       WHERE catalog_id = :catalogId
                         AND catalog -> '$array' @> jsonb_build_array(jsonb_build_object('key', CAST(:key AS text))))""",
            ).bind("catalogId", catalogId.value)
            .bind("key", key)
            .mapTo(Boolean::class.java)
            .one()
    }

    /**
     * The last stored element of [array] (`offerings` or `categories`) for every key that
     * appears in a revision up to [reference] but not in [reference] itself, in key order
     * (byte order, independent of database collation), with the revision it came from.
     */
    private fun retired(
        transaction: Transaction,
        reference: OfferingsSnapshotReference,
        array: String,
    ): List<Pair<OfferingsRevision, String>> {
        require(array == "offerings" || array == "categories") { "Unsupported catalog array: $array" }
        return transaction.handle
            .createQuery(
                """WITH current_snapshot AS (
                       SELECT catalog -> '$array' AS elements FROM commerce.offerings_snapshots
                       WHERE catalog_id = :catalogId AND revision = :revision),
                   historical AS (
                       SELECT s.revision, e.element, e.element ->> 'key' AS key
                       FROM commerce.offerings_snapshots s
                       CROSS JOIN LATERAL jsonb_array_elements(s.catalog -> '$array') AS e(element)
                       WHERE s.catalog_id = :catalogId AND s.revision <= :revision)
                   SELECT DISTINCT ON (key COLLATE "C") revision, element::text AS element
                   FROM historical
                   WHERE NOT EXISTS (
                       SELECT 1 FROM current_snapshot
                       WHERE current_snapshot.elements @> jsonb_build_array(jsonb_build_object('key', historical.key)))
                   ORDER BY key COLLATE "C", revision DESC""",
            ).bind("catalogId", reference.catalogId.value)
            .bind("revision", reference.revision.number)
            .map { rows, _ -> OfferingsRevision.of(rows.getInt("revision")) to rows.getString("element") }
            .list()
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
