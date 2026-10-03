package io.github.castab.commerce.runtime.offering

import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.OfferingsSnapshotReference
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.runtime.persistence.OfferingsSnapshotRepository
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor

/** An item read from, or written to, a catalog at the revision that holds it. */
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
            OfferingsSnapshot.create(catalogId).also { repository.save(transaction, it) }
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
            if (repository.categoryKeyReserved(transaction, catalogId, category.key)) {
                throw CommerceFailure.Conflict("Offering category ${category.key.value} is retired; restore it instead")
            }
            val next = latest.revise(latest.categories + category, latest.offerings)
            repository.save(transaction, next)
            CatalogResult(next.reference, category)
        }
}

/**
 * Appends new offering identities, in order, in one successor when the expected revision is
 * current. The batch is all or nothing: one invalid item saves nothing.
 */
class AddOfferings(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        expectedRevision: OfferingsRevision,
        offerings: List<Offering>,
    ): CatalogResult<List<Offering>> =
        transactor.inTransaction { transaction ->
            requireBatch(offerings.map { it.key })
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            requireExpectedRevision(latest, expectedRevision)
            val retired = retiredOfferingKeys(repository, transaction, catalogId)
            offerings.forEach { offering ->
                if (latest.offering(offering.key) != null) {
                    throw CommerceFailure.Conflict("Offering ${offering.key.value} already exists")
                }
                if (offering.key in retired) {
                    throw CommerceFailure.Conflict("Offering ${offering.key.value} is retired; restore it instead")
                }
                requireCategory(latest, offering.category)
            }
            val next = latest.revise(latest.categories, latest.offerings + offerings)
            repository.save(transaction, next)
            CatalogResult(next.reference, offerings.toList())
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

/**
 * Replaces active identities in one successor when the expected revision is current. Each
 * offering is a full replacement of the active offering with its key; each keeps its position.
 * The batch is all or nothing: one invalid item saves nothing.
 */
class UpdateOfferings(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        expectedRevision: OfferingsRevision,
        offerings: List<Offering>,
    ): CatalogResult<List<Offering>> =
        transactor.inTransaction { transaction ->
            requireBatch(offerings.map { it.key })
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            requireExpectedRevision(latest, expectedRevision)
            val retired = retiredOfferingKeys(repository, transaction, catalogId)
            offerings.forEach { replacement ->
                requireActiveOffering(latest, retired, replacement.key)
                requireCategory(latest, replacement.category)
            }
            val replacements = offerings.associateBy { it.key }
            val next = latest.revise(latest.categories, latest.offerings.map { replacements[it.key] ?: it })
            repository.save(transaction, next)
            CatalogResult(next.reference, offerings.toList())
        }
}

/**
 * Retires active identities in one successor when the expected revision is current. Other
 * offerings retain their order. The batch is all or nothing.
 */
class RetireOfferings(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        expectedRevision: OfferingsRevision,
        keys: List<OfferingKey>,
    ): OfferingsSnapshotReference =
        transactor.inTransaction { transaction ->
            requireBatch(keys)
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            requireExpectedRevision(latest, expectedRevision)
            val retired = retiredOfferingKeys(repository, transaction, catalogId)
            keys.forEach { requireActiveOffering(latest, retired, it) }
            val removed = keys.toSet()
            val next = latest.revise(latest.categories, latest.offerings.filterNot { it.key in removed })
            repository.save(transaction, next)
            next.reference
        }
}

/**
 * Appends retired identities, in order, in one successor when the expected revision is
 * current. Each offering is the restored identity's complete new representation. The batch is
 * all or nothing.
 */
class RestoreOfferings(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(
        catalogId: OfferingsCatalogId,
        expectedRevision: OfferingsRevision,
        offerings: List<Offering>,
    ): CatalogResult<List<Offering>> =
        transactor.inTransaction { transaction ->
            requireBatch(offerings.map { it.key })
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            requireExpectedRevision(latest, expectedRevision)
            val retired = retiredOfferingKeys(repository, transaction, catalogId)
            offerings.forEach { restored ->
                if (latest.offering(restored.key) != null) {
                    throw CommerceFailure.Conflict("Offering ${restored.key.value} is already active")
                }
                if (restored.key !in retired) {
                    throw CommerceFailure.NotFound("Offering ${restored.key.value} was not found")
                }
                requireCategory(latest, restored.category)
            }
            val next = latest.revise(latest.categories, latest.offerings + offerings)
            repository.save(transaction, next)
            CatalogResult(next.reference, offerings.toList())
        }
}

private fun retiredOfferingKeys(
    repository: OfferingsSnapshotRepository,
    transaction: Transaction,
    catalogId: OfferingsCatalogId,
): Set<OfferingKey> = repository.retrieveRetiredOfferings(transaction, catalogId).map { it.value.key }.toSet()

private fun requireActiveOffering(
    latest: OfferingsSnapshot,
    retired: Set<OfferingKey>,
    key: OfferingKey,
) {
    if (latest.offering(key) != null) return
    if (key in retired) {
        throw CommerceFailure.Conflict("Offering ${key.value} is retired; restore it before updating or retiring it")
    }
    throw CommerceFailure.NotFound("Offering ${key.value} was not found")
}

/** A batch names at least one offering and no offering twice. */
private fun requireBatch(keys: List<OfferingKey>) =
    validating {
        require(keys.isNotEmpty()) { "At least one offering is required" }
        val repeated =
            keys
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        require(repeated.isEmpty()) { "Offerings appear more than once: ${repeated.joinToString { it.value }}" }
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
            repository.save(transaction, next)
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
            repository.save(transaction, next)
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
            if (!repository.categoryKeyReserved(transaction, catalogId, key)) {
                throw CommerceFailure.NotFound("Offering category ${key.value} was not found")
            }
            val replacement = validating { OfferingCategory(key, displayName, description, minimumSelections, maximumSelections) }
            val next = latest.revise(latest.categories + replacement, latest.offerings)
            repository.save(transaction, next)
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
    if (repository.categoryKeyReserved(transaction, latest.catalogId, key)) {
        throw CommerceFailure.Conflict("Offering category ${key.value} is retired; restore it before updating or retiring it")
    }
    throw CommerceFailure.NotFound("Offering category ${key.value} was not found")
}

/** Discovers retired identities, with their last representations and last revisions, in key order. */
class ListRetiredOfferings(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(catalogId: OfferingsCatalogId): CatalogResult<List<CatalogResult<Offering>>> =
        transactor.inTransaction { transaction ->
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            CatalogResult(
                latest.reference,
                repository.retrieveRetiredOfferings(transaction, catalogId).map {
                    CatalogResult(it.lastSeen, it.value)
                },
            )
        }
}

/** Discovers retired identities, with their last representations and last revisions, in key order. */
class ListRetiredCategories(
    private val transactor: Transactor,
    private val repository: OfferingsSnapshotRepository,
) {
    operator fun invoke(catalogId: OfferingsCatalogId): CatalogResult<List<CatalogResult<OfferingCategory>>> =
        transactor.inTransaction { transaction ->
            val latest = repository.retrieveLatestVersion(transaction, catalogId) ?: missingCatalog(catalogId)
            CatalogResult(
                latest.reference,
                repository.retrieveRetiredCategories(transaction, catalogId).map {
                    CatalogResult(it.lastSeen, it.value)
                },
            )
        }
}

/** Client precondition, checked in the same transaction that saves the successor. The repository's row lock still guards true races. */
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
