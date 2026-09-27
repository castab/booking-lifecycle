package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingPrice
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
                            price_kind, price_amount, price_currency, quantity_dimension, duration_seconds, duration_nanos)
                           VALUES (:catalogId, :revision, :key, :category, :position, :name, :description,
                                   :priceKind, :priceAmount, :priceCurrency, :dimension, :seconds, :nanos)""",
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
                    .execute()
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
                          price_currency, quantity_dimension, duration_seconds, duration_nanos
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
        )
    }
}
