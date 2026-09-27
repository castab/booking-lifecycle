package io.github.castab.commerce.offering

import java.util.Collections

/** The 1-based revision of one catalog. Independent of financial document versions. */
class OfferingsRevision private constructor(
    val number: Int,
) : Comparable<OfferingsRevision> {
    fun next(): OfferingsRevision = OfferingsRevision(Math.addExact(number, 1))

    override fun compareTo(other: OfferingsRevision): Int = number.compareTo(other.number)

    override fun equals(other: Any?): Boolean = other is OfferingsRevision && number == other.number

    override fun hashCode(): Int = number

    override fun toString(): String = "r$number"

    companion object {
        val INITIAL = OfferingsRevision(1)

        fun of(number: Int): OfferingsRevision {
            require(number >= 1) { "An offerings revision must be at least 1" }
            return if (number == 1) INITIAL else OfferingsRevision(number)
        }
    }
}

/** Identifies exactly one immutable snapshot. */
data class OfferingsSnapshotReference(
    val catalogId: OfferingsCatalogId,
    val revision: OfferingsRevision,
)

/** One immutable, ordered revision of the commercial choices in a catalog. */
class OfferingsSnapshot private constructor(
    val catalogId: OfferingsCatalogId,
    val revision: OfferingsRevision,
    val previousRevision: OfferingsRevision?,
    categories: List<OfferingCategory>,
    offerings: List<Offering>,
) {
    val categories: List<OfferingCategory> = Collections.unmodifiableList(ArrayList(categories))
    val offerings: List<Offering> = Collections.unmodifiableList(ArrayList(offerings))

    init {
        require(if (previousRevision == null) revision == OfferingsRevision.INITIAL else previousRevision.next() == revision) {
            "Catalog $catalogId at $revision cannot have previous revision $previousRevision"
        }
        require(
            this.categories
                .map { it.key }
                .distinct()
                .size == this.categories.size,
        ) { "Duplicate category key" }
        require(
            this.offerings
                .map { it.key }
                .distinct()
                .size == this.offerings.size,
        ) { "Duplicate offering key" }
        val keys = this.categories.map { it.key }.toSet()
        require(this.offerings.all { it.category in keys }) { "Every offering must refer to a category in its snapshot" }
    }

    val reference: OfferingsSnapshotReference get() = OfferingsSnapshotReference(catalogId, revision)

    fun category(key: OfferingCategoryKey): OfferingCategory? = categories.find { it.key == key }

    fun offering(key: OfferingKey): Offering? = offerings.find { it.key == key }

    fun offeringsIn(category: OfferingCategoryKey): List<Offering> = offerings.filter { it.category == category }

    /** Produces the immediate successor without changing this snapshot. */
    fun revise(
        categories: List<OfferingCategory>,
        offerings: List<Offering>,
    ): OfferingsSnapshot = OfferingsSnapshot(catalogId, revision.next(), revision, categories, offerings)

    override fun equals(other: Any?): Boolean =
        other is OfferingsSnapshot &&
            catalogId == other.catalogId &&
            revision == other.revision &&
            previousRevision == other.previousRevision &&
            categories == other.categories &&
            offerings == other.offerings

    override fun hashCode(): Int = arrayOf(catalogId, revision, previousRevision, categories, offerings).contentHashCode()

    companion object {
        fun create(
            catalogId: OfferingsCatalogId,
            categories: List<OfferingCategory> = emptyList(),
            offerings: List<Offering> = emptyList(),
        ): OfferingsSnapshot = OfferingsSnapshot(catalogId, OfferingsRevision.INITIAL, null, categories, offerings)

        /** Reconstructs one stored revision; persistence must verify its predecessor exists. */
        fun restore(
            catalogId: OfferingsCatalogId,
            revision: OfferingsRevision,
            previousRevision: OfferingsRevision?,
            categories: List<OfferingCategory>,
            offerings: List<Offering>,
        ): OfferingsSnapshot = OfferingsSnapshot(catalogId, revision, previousRevision, categories, offerings)
    }
}
