package io.github.castab.commerce.runtime.offering

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingPrice
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.HistoricalCatalogValue
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.OfferingsSnapshotRepository
import io.github.castab.commerce.runtime.persistence.PostgresOfferingsSnapshotRepository
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.persistence.createDataSource
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.testApplicationMigrations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jdbi.v3.core.Jdbi
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OfferingsLifecycleSpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var dataSource: com.zaxxer.hikari.HikariDataSource
        lateinit var transactor: Transactor
        val repository: OfferingsSnapshotRepository = PostgresOfferingsSnapshotRepository()
        val flavors = OfferingCategoryKey("flavors")
        val horchata = OfferingKey("horchata")

        beforeSpec {
            database = TestDatabase.create()
            dataSource = createDataSource(database.configuration, "offerings-lifecycle-spec")
            MigrationLifecycle(dataSource, testApplicationMigrations()).migrate()
            transactor = Transactor(Jdbi.create(dataSource))
        }
        afterSpec {
            dataSource.close()
            database.close()
        }

        fun observedRevision(id: OfferingsCatalogId): OfferingsRevision =
            transactor.inTransaction { repository.retrieveLatestVersion(it, id)?.revision ?: OfferingsRevision.INITIAL }

        fun catalog(): OfferingsCatalogId =
            OfferingsCatalogId(UUID.randomUUID()).also {
                CreateOfferingsCatalog(transactor, repository)(it)
                AddOfferingCategory(transactor, repository)(it, observedRevision(it), OfferingCategory(flavors, "Flavors"))
            }

        fun latest(id: OfferingsCatalogId): OfferingsSnapshot = GetOfferingsCatalog(transactor, repository)(id)

        fun price(amount: String): OfferingPrice = OfferingPrice.Fixed(Money(BigDecimal(amount), Currency.getInstance("USD")))

        test("offering keys remain reserved through retirement and restoration with exact immutable history") {
            val id = catalog()
            val initial = latest(id)
            val offering = Offering(horchata, flavors, "Horchata", price = price("0.50"))
            AddOffering(transactor, repository)(id, observedRevision(id), offering)
            val added = latest(id)
            UpdateOffering(
                transactor,
                repository,
            )(id, observedRevision(id), horchata, flavors, "Horchata Soft Serve", price = price("0.75"))
            val updated = latest(id)
            RetireOffering(transactor, repository)(id, observedRevision(id), horchata).revision.number shouldBe 5
            val retired = latest(id)
            shouldThrow<CommerceFailure.Conflict> {
                AddOffering(transactor, repository)(id, observedRevision(id), offering.copy(displayName = "Unrelated"))
            }
            shouldThrow<CommerceFailure.Conflict> {
                UpdateOffering(
                    transactor,
                    repository,
                )(id, observedRevision(id), horchata, flavors, "Again")
            }
            shouldThrow<CommerceFailure.Conflict> { RetireOffering(transactor, repository)(id, observedRevision(id), horchata) }
            latest(id) shouldBe retired
            ListRetiredOfferings(transactor, repository)(id).let {
                it.reference shouldBe retired.reference
                it.value.shouldContainExactly(CatalogResult(updated.reference, updated.offerings.single()))
            }
            RestoreOffering(transactor, repository)(id, observedRevision(id), horchata, flavors, "Restored Horchata", price = price("1.00"))
            val restored = latest(id)
            restored.offerings.single().key shouldBe horchata
            restored.offerings.single().price shouldBe price("1.00")
            ListRetiredOfferings(transactor, repository)(id).value shouldBe emptyList()
            // Capture complete snapshots, including decimal scale and ordering, and reread after all mutations.
            listOf(initial, added, updated, retired, restored).forEach { expected ->
                GetOfferingsCatalogRevision(transactor, repository)(expected.reference) shouldBe expected
            }
            transactor.inTransaction {
                repository.offeringKeyExistsInHistory(it, id, horchata) shouldBe true
                repository.offeringKeyExistsInHistory(it, OfferingsCatalogId(UUID.randomUUID()), horchata) shouldBe false
                // A read anchored to r5 still describes r5 even after r6 has restored the item.
                repository.retrieveRetiredOfferings(it, retired.reference).shouldContainExactly(
                    HistoricalCatalogValue(updated.reference, updated.offerings.single()),
                )
            }
        }

        test("category keys remain reserved through retirement and restoration with exact immutable history") {
            val id = catalog()
            val initial = latest(id)
            UpdateOfferingCategory(transactor, repository)(id, observedRevision(id), flavors, "New flavors", "Description", 1, 2)
            val updated = latest(id)
            RetireOfferingCategory(transactor, repository)(id, observedRevision(id), flavors)
            val retired = latest(id)
            shouldThrow<CommerceFailure.Conflict> {
                AddOfferingCategory(
                    transactor,
                    repository,
                )(id, observedRevision(id), OfferingCategory(flavors, "Unrelated"))
            }
            shouldThrow<CommerceFailure.Conflict> {
                UpdateOfferingCategory(
                    transactor,
                    repository,
                )(id, observedRevision(id), flavors, "Again")
            }
            shouldThrow<CommerceFailure.Conflict> { RetireOfferingCategory(transactor, repository)(id, observedRevision(id), flavors) }
            latest(id) shouldBe retired
            ListRetiredCategories(transactor, repository)(id).value.shouldContainExactly(
                CatalogResult(updated.reference, updated.categories.single()),
            )
            RestoreOfferingCategory(transactor, repository)(id, observedRevision(id), flavors, "Restored flavors", minimumSelections = 0)
            val restored = latest(id)
            ListRetiredCategories(transactor, repository)(id).value shouldBe emptyList()
            listOf(initial, updated, retired, restored).forEach { expected ->
                GetOfferingsCatalogRevision(transactor, repository)(expected.reference) shouldBe expected
            }
            transactor.inTransaction {
                repository.categoryKeyExistsInHistory(it, id, flavors) shouldBe true
                repository.categoryKeyExistsInHistory(it, OfferingsCatalogId(UUID.randomUUID()), flavors) shouldBe false
                repository.retrieveRetiredCategories(it, retired.reference).shouldContainExactly(
                    HistoricalCatalogValue(updated.reference, updated.categories.single()),
                )
            }
        }

        listOf("update", "retire", "restore").forEach { action ->
            test("$action rejects never-seen offering and category identities without advancing revision") {
                val id = catalog()
                val before = latest(id)
                shouldThrow<CommerceFailure.NotFound> {
                    when (action) {
                        "update" -> UpdateOffering(transactor, repository)(id, observedRevision(id), horchata, flavors, "Unknown")
                        "retire" -> RetireOffering(transactor, repository)(id, observedRevision(id), horchata)
                        else -> RestoreOffering(transactor, repository)(id, observedRevision(id), horchata, flavors, "Unknown")
                    }
                }
                val missing = OfferingCategoryKey("missing")
                shouldThrow<CommerceFailure.NotFound> {
                    when (action) {
                        "update" -> UpdateOfferingCategory(transactor, repository)(id, observedRevision(id), missing, "Unknown")
                        "retire" -> RetireOfferingCategory(transactor, repository)(id, observedRevision(id), missing)
                        else -> RestoreOfferingCategory(transactor, repository)(id, observedRevision(id), missing, "Unknown")
                    }
                }
                latest(id) shouldBe before
            }
        }

        test("add and restore reject active identities and missing catalogs remain not found") {
            val id = catalog()
            val offering = Offering(horchata, flavors, "Horchata")
            AddOffering(transactor, repository)(id, observedRevision(id), offering)
            val before = latest(id)
            shouldThrow<CommerceFailure.Conflict> { AddOffering(transactor, repository)(id, observedRevision(id), offering) }
            shouldThrow<CommerceFailure.Conflict> {
                AddOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategory(flavors, "Flavors"))
            }
            shouldThrow<CommerceFailure.Conflict> {
                RestoreOffering(
                    transactor,
                    repository,
                )(id, observedRevision(id), horchata, flavors, "Horchata")
            }
            shouldThrow<CommerceFailure.Conflict> {
                RestoreOfferingCategory(
                    transactor,
                    repository,
                )(id, observedRevision(id), flavors, "Flavors")
            }
            latest(id) shouldBe before
            val missing = OfferingsCatalogId(UUID.randomUUID())
            shouldThrow<CommerceFailure.NotFound> { ListRetiredOfferings(transactor, repository)(missing) }
            shouldThrow<CommerceFailure.NotFound> { ListRetiredCategories(transactor, repository)(missing) }
            shouldThrow<CommerceFailure.NotFound> {
                UpdateOffering(transactor, repository)(missing, observedRevision(missing), horchata, flavors, "Horchata")
            }
            shouldThrow<CommerceFailure.NotFound> {
                RetireOfferingCategory(
                    transactor,
                    repository,
                )(missing, observedRevision(missing), flavors)
            }
        }

        test("updates preserve positions, retirement preserves other order, and add and restore append") {
            val id = catalog()
            listOf("second", "third").forEach {
                AddOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategory(OfferingCategoryKey(it), it))
            }
            listOf("vanilla", "horchata", "chocolate").forEach {
                AddOffering(transactor, repository)(id, observedRevision(id), Offering(OfferingKey(it), flavors, it))
            }
            UpdateOffering(transactor, repository)(id, observedRevision(id), horchata, flavors, "Changed")
            UpdateOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategoryKey("second"), "Changed")
            latest(id).offerings.map { it.key.value }.shouldContainExactly("vanilla", "horchata", "chocolate")
            latest(id).categories.map { it.key.value }.shouldContainExactly("flavors", "second", "third")
            RetireOffering(transactor, repository)(id, observedRevision(id), horchata)
            RetireOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategoryKey("second"))
            latest(id).offerings.map { it.key.value }.shouldContainExactly("vanilla", "chocolate")
            latest(id).categories.map { it.key.value }.shouldContainExactly("flavors", "third")
            RestoreOffering(transactor, repository)(id, observedRevision(id), horchata, flavors, "Restored")
            RestoreOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategoryKey("second"), "Restored")
            AddOffering(transactor, repository)(id, observedRevision(id), Offering(OfferingKey("last"), flavors, "Last"))
            AddOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategory(OfferingCategoryKey("last"), "Last"))
            latest(id).offerings.map { it.key.value }.shouldContainExactly("vanilla", "chocolate", "horchata", "last")
            latest(id).categories.map { it.key.value }.shouldContainExactly("flavors", "third", "second", "last")
        }

        test("category retirement never cascades and offering moves and restores require a current category") {
            val id = catalog()
            AddOffering(transactor, repository)(id, observedRevision(id), Offering(horchata, flavors, "Horchata"))
            val original = latest(id)
            shouldThrow<CommerceFailure.Conflict> { RetireOfferingCategory(transactor, repository)(id, observedRevision(id), flavors) }
            val missing = OfferingCategoryKey("missing")
            shouldThrow<CommerceFailure.NotFound> {
                UpdateOffering(
                    transactor,
                    repository,
                )(id, observedRevision(id), horchata, missing, "Horchata")
            }
            val target = OfferingCategoryKey("target")
            AddOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategory(target, "Target"))
            RetireOfferingCategory(transactor, repository)(id, observedRevision(id), target)
            shouldThrow<CommerceFailure.NotFound> {
                UpdateOffering(
                    transactor,
                    repository,
                )(id, observedRevision(id), horchata, target, "Horchata")
            }
            RetireOffering(transactor, repository)(id, observedRevision(id), horchata)
            shouldThrow<CommerceFailure.NotFound> {
                RestoreOffering(
                    transactor,
                    repository,
                )(id, observedRevision(id), horchata, missing, "Horchata")
            }
            shouldThrow<CommerceFailure.NotFound> {
                RestoreOffering(
                    transactor,
                    repository,
                )(id, observedRevision(id), horchata, target, "Horchata")
            }
            RetireOfferingCategory(transactor, repository)(id, observedRevision(id), flavors)
            RestoreOfferingCategory(transactor, repository)(id, observedRevision(id), target, "Target")
            RestoreOffering(transactor, repository)(id, observedRevision(id), horchata, target, "Horchata")
            RestoreOfferingCategory(transactor, repository)(id, observedRevision(id), flavors, "Flavors")
            UpdateOffering(transactor, repository)(id, observedRevision(id), horchata, flavors, "Horchata")
            RetireOfferingCategory(transactor, repository)(id, observedRevision(id), target)
            GetOfferingsCatalogRevision(transactor, repository)(original.reference) shouldBe original
        }

        test("retired discovery is catalog scoped, ordered by key, and returns each last complete representation") {
            val id = catalog()
            val other = catalog()
            listOf("z", "a").forEach {
                AddOffering(transactor, repository)(id, observedRevision(id), Offering(OfferingKey(it), flavors, it))
            }
            UpdateOffering(
                transactor,
                repository,
            )(id, observedRevision(id), OfferingKey("a"), flavors, "Last A", "Description", price("2.5000"))
            val lastA = latest(id)
            RetireOffering(transactor, repository)(id, observedRevision(id), OfferingKey("a"))
            val lastZ = latest(id)
            RetireOffering(transactor, repository)(id, observedRevision(id), OfferingKey("z"))
            AddOffering(transactor, repository)(other, observedRevision(other), Offering(OfferingKey("a"), flavors, "Other catalog"))
            val retired = ListRetiredOfferings(transactor, repository)(id)
            retired.value.map { it.value.key.value }.shouldContainExactly("a", "z")
            retired.value[0] shouldBe CatalogResult(lastA.reference, lastA.offering(OfferingKey("a"))!!)
            retired.value[1] shouldBe CatalogResult(lastZ.reference, lastZ.offering(OfferingKey("z"))!!)
            ListRetiredOfferings(transactor, repository)(other).value shouldBe emptyList()
            listOf("z", "a").forEach {
                AddOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategory(OfferingCategoryKey(it), it))
            }
            listOf("a", "z").forEach { RetireOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategoryKey(it)) }
            ListRetiredCategories(transactor, repository)(id).value.map { it.value.key.value }.shouldContainExactly("a", "z")
            ListRetiredCategories(transactor, repository)(other).value shouldBe emptyList()
        }

        test("invalid editable values leave all history unchanged") {
            val id = catalog()
            AddOffering(transactor, repository)(id, observedRevision(id), Offering(horchata, flavors, "Horchata"))
            val before = latest(id)
            shouldThrow<CommerceFailure.ValidationFailed> {
                UpdateOffering(
                    transactor,
                    repository,
                )(id, observedRevision(id), horchata, flavors, " ")
            }
            shouldThrow<CommerceFailure.ValidationFailed> {
                UpdateOfferingCategory(transactor, repository)(id, observedRevision(id), flavors, "Flavors", minimumSelections = -1)
            }
            latest(id) shouldBe before
            RetireOffering(transactor, repository)(id, observedRevision(id), horchata)
            RetireOfferingCategory(transactor, repository)(id, observedRevision(id), flavors)
            val retired = latest(id)
            shouldThrow<CommerceFailure.ValidationFailed> {
                RestoreOfferingCategory(
                    transactor,
                    repository,
                )(id, observedRevision(id), flavors, " ")
            }
            latest(id) shouldBe retired
            RestoreOfferingCategory(transactor, repository)(id, observedRevision(id), flavors, "Flavors")
            val restoredCategory = latest(id)
            shouldThrow<CommerceFailure.ValidationFailed> {
                RestoreOffering(
                    transactor,
                    repository,
                )(id, observedRevision(id), horchata, flavors, " ")
            }
            latest(id) shouldBe restoredCategory
        }

        test("a stale replacement cannot undo a committed price update or create another revision") {
            val id = catalog()
            AddOffering(transactor, repository)(id, observedRevision(id), Offering(horchata, flavors, "Horchata", price = price("0.50")))
            val uiObserved = latest(id)
            UpdateOffering(transactor, repository)(id, uiObserved.revision, horchata, flavors, "Horchata", price = price("0.75"))
            val committed = latest(id)
            shouldThrow<CommerceFailure.Conflict> {
                UpdateOffering(
                    transactor,
                    repository,
                )(id, uiObserved.revision, horchata, flavors, "New display name", price = price("0.50"))
            }
            latest(id) shouldBe committed
            latest(id).offerings.single().price shouldBe price("0.75")
            shouldThrow<CommerceFailure.NotFound> {
                GetOfferingsCatalogRevision(transactor, repository)(committed.reference.copy(revision = committed.revision.next()))
            }
            GetOfferingsCatalogRevision(transactor, repository)(uiObserved.reference) shouldBe uiObserved
        }

        listOf(
            "addOffering",
            "addCategory",
            "updateOffering",
            "retireOffering",
            "restoreOffering",
            "updateCategory",
            "retireCategory",
            "restoreCategory",
        ).forEach { action ->
            test("$action rejects stale client revisions before a successor is created") {
                val id = catalog()
                AddOffering(transactor, repository)(id, observedRevision(id), Offering(horchata, flavors, "Horchata"))
                if (action in setOf("restoreOffering", "retireCategory", "restoreCategory")) {
                    RetireOffering(transactor, repository)(id, observedRevision(id), horchata)
                }
                if (action == "restoreCategory") RetireOfferingCategory(transactor, repository)(id, observedRevision(id), flavors)
                val uiObserved = latest(id)
                AddOfferingCategory(
                    transactor,
                    repository,
                )(id, uiObserved.revision, OfferingCategory(OfferingCategoryKey("other"), "Other"))
                val committed = latest(id)
                shouldThrow<CommerceFailure.Conflict> {
                    when (action) {
                        "addOffering" ->
                            AddOffering(
                                transactor,
                                repository,
                            )(id, uiObserved.revision, Offering(OfferingKey("new"), flavors, "New"))
                        "addCategory" ->
                            AddOfferingCategory(
                                transactor,
                                repository,
                            )(id, uiObserved.revision, OfferingCategory(OfferingCategoryKey("new"), "New"))
                        "updateOffering" -> UpdateOffering(transactor, repository)(id, uiObserved.revision, horchata, flavors, "New")
                        "retireOffering" -> RetireOffering(transactor, repository)(id, uiObserved.revision, horchata)
                        "restoreOffering" -> RestoreOffering(transactor, repository)(id, uiObserved.revision, horchata, flavors, "New")
                        "updateCategory" -> UpdateOfferingCategory(transactor, repository)(id, uiObserved.revision, flavors, "New")
                        "retireCategory" -> RetireOfferingCategory(transactor, repository)(id, uiObserved.revision, flavors)
                        else -> RestoreOfferingCategory(transactor, repository)(id, uiObserved.revision, flavors, "New")
                    }
                }
                latest(id) shouldBe committed
                GetOfferingsCatalogRevision(transactor, repository)(uiObserved.reference) shouldBe uiObserved
                shouldThrow<CommerceFailure.NotFound> {
                    GetOfferingsCatalogRevision(transactor, repository)(committed.reference.copy(revision = committed.revision.next()))
                }
                transactor.inTransaction {
                    it.handle
                        .createQuery("SELECT count(*) FROM commerce.offerings_snapshots WHERE catalog_id = :id")
                        .bind("id", id.value)
                        .mapTo(Int::class.java)
                        .one() shouldBe committed.revision.number
                }
            }
        }

        listOf("update", "retire", "restore").forEach { action ->
            test("competing $action writers from one revision produce one successor and one conflict") {
                val id = catalog()
                AddOffering(transactor, repository)(id, observedRevision(id), Offering(horchata, flavors, "Horchata"))
                if (action == "restore") RetireOffering(transactor, repository)(id, observedRevision(id), horchata)
                val before = latest(id)
                val barrier = CyclicBarrier(2)
                val synchronizedReads =
                    object : OfferingsSnapshotRepository by repository {
                        override fun retrieveLatestVersion(
                            transaction: Transaction,
                            catalogId: OfferingsCatalogId,
                        ): OfferingsSnapshot? =
                            repository.retrieveLatestVersion(transaction, catalogId).also { barrier.await(10, TimeUnit.SECONDS) }
                    }
                Executors.newFixedThreadPool(2).use { executor ->
                    val results =
                        (1..2)
                            .map { writer ->
                                executor.submit<String> {
                                    try {
                                        when (action) {
                                            "update" ->
                                                UpdateOffering(
                                                    transactor,
                                                    synchronizedReads,
                                                )(id, before.revision, horchata, flavors, "Writer $writer")
                                            "retire" -> RetireOffering(transactor, synchronizedReads)(id, before.revision, horchata)
                                            else ->
                                                RestoreOffering(
                                                    transactor,
                                                    synchronizedReads,
                                                )(id, before.revision, horchata, flavors, "Writer $writer")
                                        }
                                        "success"
                                    } catch (_: CommerceFailure.Conflict) {
                                        "conflict"
                                    }
                                }
                            }.map { it.get(20, TimeUnit.SECONDS) }
                    results.sorted().shouldContainExactly("conflict", "success")
                }
                latest(id).revision shouldBe before.revision.next()
                GetOfferingsCatalogRevision(transactor, repository)(before.reference) shouldBe before
                transactor.inTransaction {
                    it.handle
                        .createQuery("SELECT count(*) FROM commerce.offerings_snapshots WHERE catalog_id = :id")
                        .bind("id", id.value)
                        .mapTo(Int::class.java)
                        .one() shouldBe before.revision.number + 1
                }
            }
        }
    })
