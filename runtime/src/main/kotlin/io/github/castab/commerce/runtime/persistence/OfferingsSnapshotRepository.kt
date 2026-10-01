package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingAvailability
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingPrice
import io.github.castab.commerce.offering.OfferingSelectionState
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.OfferingsSnapshotReference
import io.github.castab.commerce.offering.QuantityDimension
import io.github.castab.commerce.runtime.operation.CommerceFailure
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.sql.ResultSet
import java.time.Duration
import java.util.Currency

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
            transaction.handle
                .createUpdate(
                    """INSERT INTO commerce.offerings_snapshots (catalog_id, revision, previous_revision)
                       VALUES (:catalogId, :revision, :previousRevision)""",
                ).bind("catalogId", snapshot.catalogId.value)
                .bind("revision", snapshot.revision.number)
                .bind("previousRevision", snapshot.previousRevision?.number)
                .execute()
            snapshot.categories.forEachIndexed { position, category ->
                transaction.handle
                    .createUpdate(
                        """INSERT INTO commerce.offering_categories
                           (catalog_id, revision, category_key, position, display_name, description,
                            minimum_selections, maximum_selections)
                           VALUES (:catalogId, :revision, :key, :position, :name, :description, :minimum, :maximum)""",
                    ).bind("catalogId", snapshot.catalogId.value)
                    .bind("revision", snapshot.revision.number)
                    .bind("key", category.key.value)
                    .bind("position", position)
                    .bind("name", category.displayName)
                    .bind("description", category.description)
                    .bind("minimum", category.minimumSelections)
                    .bind("maximum", category.maximumSelections)
                    .execute()
            }
            snapshot.offerings.forEachIndexed { position, offering ->
                val price = offering.price
                val amount =
                    when (price) {
                        null -> null
                        is OfferingPrice.Fixed -> price.amount
                        is OfferingPrice.PerQuantity -> price.amount
                        is OfferingPrice.PerDuration -> price.amount
                    }
                transaction.handle
                    .createUpdate(
                        """INSERT INTO commerce.offerings
                           (catalog_id, revision, offering_key, category_key, position, display_name, description,
                            price_kind, price_amount, price_currency, quantity_dimension, duration_seconds, duration_nanos, selection_state, availability)
                           VALUES (:catalogId, :revision, :key, :category, :position, :name, :description,
                                   :priceKind, :priceAmount, :priceCurrency, :dimension, :seconds, :nanos, :selectionState, :availability)""",
                    ).bind("catalogId", snapshot.catalogId.value)
                    .bind("revision", snapshot.revision.number)
                    .bind("key", offering.key.value)
                    .bind("category", offering.category.value)
                    .bind("position", position)
                    .bind("name", offering.displayName)
                    .bind("description", offering.description)
                    .bind(
                        "priceKind",
                        when (price) {
                            null -> null
                            is OfferingPrice.Fixed -> "FIXED"
                            is OfferingPrice.PerQuantity -> "PER_QUANTITY"
                            is OfferingPrice.PerDuration -> "PER_DURATION"
                        },
                    ).bind("priceAmount", amount?.amount)
                    .bind("priceCurrency", amount?.currency?.currencyCode)
                    .bind("dimension", (price as? OfferingPrice.PerQuantity)?.dimension?.value)
                    .bind("seconds", (price as? OfferingPrice.PerDuration)?.interval?.seconds)
                    .bind("nanos", (price as? OfferingPrice.PerDuration)?.interval?.nano)
                    .bind(
                        "selectionState",
                        when (offering.selectionState) {
                            OfferingSelectionState.ENABLED -> "ENABLED"
                            OfferingSelectionState.DISABLED -> "DISABLED"
                        },
                    ).bind(
                        "availability",
                        when (offering.availability) {
                            OfferingAvailability.AVAILABLE -> "AVAILABLE"
                            OfferingAvailability.UNAVAILABLE -> "UNAVAILABLE"
                        },
                    ).execute()
            }
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) throw CommerceFailure.Conflict("Offerings snapshot ${snapshot.reference} already exists", e)
            throw e
        }
    }

    override fun retrieveVersion(
        transaction: Transaction,
        reference: OfferingsSnapshotReference,
    ): OfferingsSnapshot? {
        val previous =
            transaction.handle
                .createQuery(
                    """SELECT revision, previous_revision FROM commerce.offerings_snapshots
                   WHERE catalog_id = :catalogId AND revision = :revision""",
                ).bind("catalogId", reference.catalogId.value)
                .bind("revision", reference.revision.number)
                .map { rows, _ ->
                    rows.getInt("revision") to rows.getObject("previous_revision", Integer::class.java)?.toInt()
                }.findOne()
        if (previous.isEmpty) return null
        val categories =
            transaction.handle
                .createQuery(
                    """SELECT category_key, display_name, description, minimum_selections, maximum_selections
                   FROM commerce.offering_categories WHERE catalog_id = :catalogId AND revision = :revision
                   ORDER BY position""",
                ).bind("catalogId", reference.catalogId.value)
                .bind("revision", reference.revision.number)
                .map { rows, _ -> category(rows) }
                .list()
        val offerings =
            transaction.handle
                .createQuery(
                    """SELECT offering_key, category_key, display_name, description, price_kind, price_amount,
                          price_currency, quantity_dimension, duration_seconds, duration_nanos, selection_state, availability
                   FROM commerce.offerings WHERE catalog_id = :catalogId AND revision = :revision
                   ORDER BY position""",
                ).bind("catalogId", reference.catalogId.value)
                .bind("revision", reference.revision.number)
                .map { rows, _ -> offering(rows) }
                .list()
        return OfferingsSnapshot.restore(
            reference.catalogId,
            reference.revision,
            previous.get().second?.let(OfferingsRevision::of),
            categories,
            offerings,
        )
    }

    override fun retrieveLatestVersion(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
    ): OfferingsSnapshot? {
        val revision =
            transaction.handle
                .createQuery("SELECT max(revision) FROM commerce.offerings_snapshots WHERE catalog_id = :catalogId")
                .bind("catalogId", catalogId.value)
                .map { rows, _ -> rows.getObject(1, Integer::class.java)?.toInt() }
                .one() ?: return null
        return retrieveVersion(transaction, OfferingsSnapshotReference(catalogId, OfferingsRevision.of(revision)))
    }

    override fun offeringKeyExistsInHistory(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        key: OfferingKey,
    ): Boolean =
        transaction.handle
            .createQuery("SELECT EXISTS (SELECT 1 FROM commerce.offerings WHERE catalog_id = :catalogId AND offering_key = :key)")
            .bind("catalogId", catalogId.value)
            .bind("key", key.value)
            .mapTo(Boolean::class.java)
            .one()

    override fun categoryKeyExistsInHistory(
        transaction: Transaction,
        catalogId: OfferingsCatalogId,
        key: OfferingCategoryKey,
    ): Boolean =
        transaction.handle
            .createQuery("SELECT EXISTS (SELECT 1 FROM commerce.offering_categories WHERE catalog_id = :catalogId AND category_key = :key)")
            .bind("catalogId", catalogId.value)
            .bind("key", key.value)
            .mapTo(Boolean::class.java)
            .one()

    override fun retrieveRetiredOfferings(
        transaction: Transaction,
        reference: OfferingsSnapshotReference,
    ): List<HistoricalCatalogValue<Offering>> =
        transaction.handle
            .createQuery(
                """SELECT DISTINCT ON (historical.offering_key COLLATE "C") historical.*
                   FROM commerce.offerings historical
                   WHERE historical.catalog_id = :catalogId AND historical.revision <= :revision
                     AND NOT EXISTS (
                         SELECT 1 FROM commerce.offerings current
                         WHERE current.catalog_id = historical.catalog_id
                           AND current.revision = :revision AND current.offering_key = historical.offering_key
                     )
                   ORDER BY historical.offering_key COLLATE "C", historical.revision DESC""",
            ).bind("catalogId", reference.catalogId.value)
            .bind("revision", reference.revision.number)
            .map { rows, _ ->
                HistoricalCatalogValue(
                    OfferingsSnapshotReference(reference.catalogId, OfferingsRevision.of(rows.getInt("revision"))),
                    offering(rows),
                )
            }.list()

    override fun retrieveRetiredCategories(
        transaction: Transaction,
        reference: OfferingsSnapshotReference,
    ): List<HistoricalCatalogValue<OfferingCategory>> =
        transaction.handle
            .createQuery(
                """SELECT DISTINCT ON (historical.category_key COLLATE "C") historical.*
                   FROM commerce.offering_categories historical
                   WHERE historical.catalog_id = :catalogId AND historical.revision <= :revision
                     AND NOT EXISTS (
                         SELECT 1 FROM commerce.offering_categories current
                         WHERE current.catalog_id = historical.catalog_id
                           AND current.revision = :revision AND current.category_key = historical.category_key
                     )
                   ORDER BY historical.category_key COLLATE "C", historical.revision DESC""",
            ).bind("catalogId", reference.catalogId.value)
            .bind("revision", reference.revision.number)
            .map { rows, _ ->
                HistoricalCatalogValue(
                    OfferingsSnapshotReference(reference.catalogId, OfferingsRevision.of(rows.getInt("revision"))),
                    category(rows),
                )
            }.list()

    private fun category(rows: ResultSet): OfferingCategory =
        OfferingCategory(
            OfferingCategoryKey(rows.getString("category_key")),
            rows.getString("display_name"),
            rows.getString("description"),
            rows.getInt("minimum_selections"),
            rows.getObject("maximum_selections", Integer::class.java)?.toInt(),
        )

    private fun offering(rows: ResultSet): Offering {
        val kind = rows.getString("price_kind")
        val amount =
            if (kind ==
                null
            ) {
                null
            } else {
                Money(rows.getBigDecimal("price_amount"), Currency.getInstance(rows.getString("price_currency").trim()))
            }
        val price =
            when (kind) {
                null -> null
                "FIXED" -> OfferingPrice.Fixed(amount!!)
                "PER_QUANTITY" -> OfferingPrice.PerQuantity(amount!!, QuantityDimension(rows.getString("quantity_dimension")))
                "PER_DURATION" ->
                    OfferingPrice.PerDuration(
                        amount!!,
                        Duration.ofSeconds(rows.getLong("duration_seconds"), rows.getInt("duration_nanos").toLong()),
                    )
                else -> error("Unsupported offerings price kind: $kind")
            }
        return Offering(
            OfferingKey(rows.getString("offering_key")),
            OfferingCategoryKey(rows.getString("category_key")),
            rows.getString("display_name"),
            rows.getString("description"),
            price,
            when (val selectionState = rows.getString("selection_state")) {
                "ENABLED" -> OfferingSelectionState.ENABLED
                "DISABLED" -> OfferingSelectionState.DISABLED
                else -> error("Unsupported offering selection state: $selectionState")
            },
            when (val availability = rows.getString("availability")) {
                "AVAILABLE" -> OfferingAvailability.AVAILABLE
                "UNAVAILABLE" -> OfferingAvailability.UNAVAILABLE
                else -> error("Unsupported offering availability: $availability")
            },
        )
    }
}
