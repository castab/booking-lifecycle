package io.github.castab.commerce.runtime.offering

import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.OfferingsSnapshotReference
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.OfferingsSnapshotRepository
import io.github.castab.commerce.runtime.persistence.Transactor

/** An item read from, or appended to, one immutable catalog revision. */
data class CatalogResult<T>(
    val reference: OfferingsSnapshotReference,
    val value: T,
)

class CreateOfferingsCatalog(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(catalogId: OfferingsCatalogId): OfferingsSnapshot =
        transactor.inTransaction { transaction ->
            if (repository.retrieveLatestVersion(transaction, catalogId) != null) {
                throw CommerceFailure.Conflict("Offerings catalog $catalogId already exists")
            }
            OfferingsSnapshot.create(catalogId).also { repository.insert(transaction, it) }
        }
}

class AddOfferingCategory(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        category: OfferingCategory,
    ): CatalogResult<OfferingCategory> =
        transactor.inTransaction { transaction ->
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            if (latest.category(category.key) != null) {
                throw CommerceFailure.Conflict("Offering category ${category.key.value} already exists")
            }
            val next = latest.revise(latest.categories + category, latest.offerings)
            repository.insert(transaction, next)
            CatalogResult(next.reference, category)
        }
}

class AddOffering(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        offering: Offering,
    ): CatalogResult<Offering> =
        transactor.inTransaction { transaction ->
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            if (latest.category(offering.category) == null) {
                throw CommerceFailure.NotFound("Offering category ${offering.category.value} was not found")
            }
            if (latest.offering(offering.key) != null) {
                throw CommerceFailure.Conflict("Offering ${offering.key.value} already exists")
            }
            val next = latest.revise(latest.categories, latest.offerings + offering)
            repository.insert(transaction, next)
            CatalogResult(next.reference, offering)
        }
}

class GetOfferingsCatalog(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(catalogId: OfferingsCatalogId): OfferingsSnapshot =
        transactor.inTransaction { transaction ->
            repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
        }
}

class GetOfferingsCatalogRevision(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(reference: OfferingsSnapshotReference): OfferingsSnapshot =
        transactor.inTransaction { transaction ->
            repository.retrieveVersion(transaction, reference)
                ?: throw CommerceFailure.NotFound("Offerings catalog revision ${reference.revision} was not found")
        }
}

class ListOfferingCategories(
    private val getCatalog: GetOfferingsCatalog,
) {
    operator fun invoke(catalogId: OfferingsCatalogId): CatalogResult<List<OfferingCategory>> =
        getCatalog(catalogId).let { CatalogResult(it.reference, it.categories) }
}

class GetOfferingCategory(
    private val getCatalog: GetOfferingsCatalog,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        key: OfferingCategoryKey,
    ): CatalogResult<OfferingCategory> =
        getCatalog(catalogId).let { snapshot ->
            CatalogResult(
                snapshot.reference,
                snapshot.category(key) ?: throw CommerceFailure.NotFound("Offering category ${key.value} was not found"),
            )
        }
}

data class CategoryOfferings(
    val category: OfferingCategory,
    val offerings: List<Offering>,
)

class ListCategoryOfferings(
    private val getCatalog: GetOfferingsCatalog,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        key: OfferingCategoryKey,
    ): CatalogResult<CategoryOfferings> =
        getCatalog(catalogId).let { snapshot ->
            val category = snapshot.category(key) ?: throw CommerceFailure.NotFound("Offering category ${key.value} was not found")
            CatalogResult(snapshot.reference, CategoryOfferings(category, snapshot.offeringsIn(key)))
        }
}

class ListOfferings(
    private val getCatalog: GetOfferingsCatalog,
) {
    operator fun invoke(catalogId: OfferingsCatalogId): CatalogResult<List<Offering>> =
        getCatalog(catalogId).let { CatalogResult(it.reference, it.offerings) }
}

class GetOffering(
    private val getCatalog: GetOfferingsCatalog,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        key: OfferingKey,
    ): CatalogResult<Offering> =
        getCatalog(catalogId).let { snapshot ->
            CatalogResult(
                snapshot.reference,
                snapshot.offering(key) ?: throw CommerceFailure.NotFound("Offering ${key.value} was not found"),
            )
        }
}

private fun missingCatalog(catalogId: OfferingsCatalogId): Nothing =
    throw CommerceFailure.NotFound("Offerings catalog ${catalogId.value} was not found")
