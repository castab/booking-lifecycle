package io.github.castab.commerce.runtime.offering

import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingPrice
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.OfferingsSnapshotReference
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.runtime.persistence.OfferingsSnapshotRepository
import io.github.castab.commerce.runtime.persistence.Transaction
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
        expectedRevision: OfferingsRevision,
        category: OfferingCategory,
    ): CatalogResult<OfferingCategory> =
        transactor.inTransaction { transaction ->
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            requireExpectedRevision(latest, expectedRevision)
            if (latest.category(category.key) != null) {
                throw CommerceFailure.Conflict("Offering category ${category.key.value} already exists")
            }
            if (repository.categoryKeyExistsInHistory(transaction, catalogId, category.key)) {
                throw CommerceFailure.Conflict("Offering category ${category.key.value} exists historically; restore it instead")
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
        expectedRevision: OfferingsRevision,
        offering: Offering,
    ): CatalogResult<Offering> =
        transactor.inTransaction { transaction ->
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            requireExpectedRevision(latest, expectedRevision)
            if (latest.offering(offering.key) != null) {
                throw CommerceFailure.Conflict("Offering ${offering.key.value} already exists")
            }
            if (repository.offeringKeyExistsInHistory(transaction, catalogId, offering.key)) {
                throw CommerceFailure.Conflict("Offering ${offering.key.value} exists historically; restore it instead")
            }
            requireCategory(latest, offering.category)
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

/** Replaces an active identity in place only when the caller's expected catalog revision is current. */
class UpdateOffering(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        expectedRevision: OfferingsRevision,
        key: OfferingKey,
        category: OfferingCategoryKey,
        displayName: String,
        description: String? = null,
        price: OfferingPrice? = null,
    ): CatalogResult<Offering> =
        transactor.inTransaction { transaction ->
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            requireExpectedRevision(latest, expectedRevision)
            requireActiveOffering(repository, transaction, latest, key)
            requireCategory(latest, category)
            val replacement = validating { Offering(key, category, displayName, description, price) }
            val next = latest.replaceOffering(key, replacement)
            repository.insert(transaction, next)
            CatalogResult(next.reference, replacement)
        }
}

/** Retires an active identity only when the caller's expected catalog revision is current. */
class RetireOffering(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        expectedRevision: OfferingsRevision,
        key: OfferingKey,
    ): OfferingsSnapshotReference =
        transactor.inTransaction { transaction ->
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            requireExpectedRevision(latest, expectedRevision)
            requireActiveOffering(repository, transaction, latest, key)
            val next = latest.withoutOffering(key)
            repository.insert(transaction, next)
            next.reference
        }
}

/** Appends a retired identity only when the caller's expected catalog revision is current. */
class RestoreOffering(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        expectedRevision: OfferingsRevision,
        key: OfferingKey,
        category: OfferingCategoryKey,
        displayName: String,
        description: String? = null,
        price: OfferingPrice? = null,
    ): CatalogResult<Offering> =
        transactor.inTransaction { transaction ->
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            requireExpectedRevision(latest, expectedRevision)
            if (latest.offering(key) != null) {
                throw CommerceFailure.Conflict("Offering ${key.value} is already active")
            }
            if (!repository.offeringKeyExistsInHistory(transaction, catalogId, key)) {
                throw CommerceFailure.NotFound("Offering ${key.value} was not found")
            }
            requireCategory(latest, category)
            val replacement = validating { Offering(key, category, displayName, description, price) }
            val next = latest.revise(latest.categories, latest.offerings + replacement)
            repository.insert(transaction, next)
            CatalogResult(next.reference, replacement)
        }
}

private fun requireActiveOffering(
    repository: OfferingsSnapshotRepository,
    transaction: Transaction,
    latest: OfferingsSnapshot,
    key: OfferingKey,
) {
    if (latest.offering(key) != null) return
    if (repository.offeringKeyExistsInHistory(transaction, latest.catalogId, key)) {
        throw CommerceFailure.Conflict("Offering ${key.value} is retired; restore it before updating or retiring it")
    }
    throw CommerceFailure.NotFound("Offering ${key.value} was not found")
}

/** Replaces an active identity in place only when the caller's expected catalog revision is current. */
class UpdateOfferingCategory(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        expectedRevision: OfferingsRevision,
        key: OfferingCategoryKey,
        displayName: String,
        description: String? = null,
        minimumSelections: Int = 0,
        maximumSelections: Int? = null,
    ): CatalogResult<OfferingCategory> =
        transactor.inTransaction { transaction ->
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            requireExpectedRevision(latest, expectedRevision)
            requireActiveOfferingCategory(repository, transaction, latest, key)
            val replacement = validating { OfferingCategory(key, displayName, description, minimumSelections, maximumSelections) }
            val next = latest.replaceCategory(key, replacement)
            repository.insert(transaction, next)
            CatalogResult(next.reference, replacement)
        }
}

/** Retires an active identity only when the caller's expected catalog revision is current. */
class RetireOfferingCategory(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        expectedRevision: OfferingsRevision,
        key: OfferingCategoryKey,
    ): OfferingsSnapshotReference =
        transactor.inTransaction { transaction ->
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            requireExpectedRevision(latest, expectedRevision)
            requireActiveOfferingCategory(repository, transaction, latest, key)
            if (latest.offeringsIn(key).isNotEmpty()) {
                throw CommerceFailure.Conflict("Offering category ${key.value} still contains offerings; retire or move them first")
            }
            val next = latest.withoutCategory(key)
            repository.insert(transaction, next)
            next.reference
        }
}

/** Appends a retired identity only when the caller's expected catalog revision is current. */
class RestoreOfferingCategory(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        expectedRevision: OfferingsRevision,
        key: OfferingCategoryKey,
        displayName: String,
        description: String? = null,
        minimumSelections: Int = 0,
        maximumSelections: Int? = null,
    ): CatalogResult<OfferingCategory> =
        transactor.inTransaction { transaction ->
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            requireExpectedRevision(latest, expectedRevision)
            if (latest.category(key) != null) {
                throw CommerceFailure.Conflict("Offering category ${key.value} is already active")
            }
            if (!repository.categoryKeyExistsInHistory(transaction, catalogId, key)) {
                throw CommerceFailure.NotFound("Offering category ${key.value} was not found")
            }
            val replacement = validating { OfferingCategory(key, displayName, description, minimumSelections, maximumSelections) }
            val next = latest.revise(latest.categories + replacement, latest.offerings)
            repository.insert(transaction, next)
            CatalogResult(next.reference, replacement)
        }
}

private fun requireActiveOfferingCategory(
    repository: OfferingsSnapshotRepository,
    transaction: Transaction,
    latest: OfferingsSnapshot,
    key: OfferingCategoryKey,
) {
    if (latest.category(key) != null) return
    if (repository.categoryKeyExistsInHistory(transaction, latest.catalogId, key)) {
        throw CommerceFailure.Conflict("Offering category ${key.value} is retired; restore it before updating or retiring it")
    }
    throw CommerceFailure.NotFound("Offering category ${key.value} was not found")
}

/** Discovers retired identities at the latest revision, with their last representations in key order. */
class ListRetiredOfferings(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(catalogId: OfferingsCatalogId): CatalogResult<List<CatalogResult<Offering>>> =
        transactor.inTransaction { transaction ->
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            CatalogResult(
                latest.reference,
                repository.retrieveRetiredOfferings(transaction, latest.reference).map {
                    CatalogResult(it.reference, it.value)
                },
            )
        }
}

/** Discovers retired identities at the latest revision, with their last representations in key order. */
class ListRetiredCategories(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(catalogId: OfferingsCatalogId): CatalogResult<List<CatalogResult<OfferingCategory>>> =
        transactor.inTransaction { transaction ->
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            CatalogResult(
                latest.reference,
                repository.retrieveRetiredCategories(transaction, latest.reference).map {
                    CatalogResult(it.reference, it.value)
                },
            )
        }
}

/** Client precondition, checked in the same transaction that appends the successor. The primary key still guards true races. */
private fun requireExpectedRevision(
    latest: OfferingsSnapshot,
    expectedRevision: OfferingsRevision,
) {
    if (latest.revision != expectedRevision) {
        throw CommerceFailure.Conflict(
            "Offerings catalog ${latest.catalogId} is at ${latest.revision}, not expected $expectedRevision; reload it and retry",
        )
    }
}

private fun requireCategory(
    latest: OfferingsSnapshot,
    key: OfferingCategoryKey,
) {
    if (latest.category(key) == null) {
        throw CommerceFailure.NotFound("Offering category ${key.value} is absent; add or restore it first")
    }
}

private fun missingCatalog(catalogId: OfferingsCatalogId): Nothing =
    throw CommerceFailure.NotFound("Offerings catalog ${catalogId.value} was not found")
