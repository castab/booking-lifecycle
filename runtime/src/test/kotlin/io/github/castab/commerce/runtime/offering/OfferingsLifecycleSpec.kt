package io.github.castab.commerce.runtime.offering

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
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.OfferingsSnapshotRepository
import io.github.castab.commerce.runtime.persistence.PostgresOfferingsSnapshotRepository
import io.github.castab.commerce.runtime.persistence.RetiredCatalogValue
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

        test("independent selection transitions advance the revision and retirement retains both facts") {
            val id = catalog()
            val original =
                Offering(
                    horchata,
                    flavors,
                    "Horchata",
                    selectionState = OfferingSelectionState.ENABLED,
                    availability = OfferingAvailability.AVAILABLE,
                )
            AddOfferings(transactor, repository)(id, observedRevision(id), listOf(original))
            val states =
                listOf(
                    OfferingSelectionState.ENABLED to OfferingAvailability.AVAILABLE,
                    OfferingSelectionState.ENABLED to OfferingAvailability.UNAVAILABLE,
                    OfferingSelectionState.DISABLED to OfferingAvailability.UNAVAILABLE,
                    OfferingSelectionState.DISABLED to OfferingAvailability.AVAILABLE,
                    OfferingSelectionState.ENABLED to OfferingAvailability.AVAILABLE,
                    OfferingSelectionState.ENABLED to OfferingAvailability.UNAVAILABLE,
                    OfferingSelectionState.DISABLED to OfferingAvailability.UNAVAILABLE,
                    OfferingSelectionState.ENABLED to OfferingAvailability.UNAVAILABLE,
                    OfferingSelectionState.DISABLED to OfferingAvailability.UNAVAILABLE,
                    OfferingSelectionState.DISABLED to OfferingAvailability.AVAILABLE,
                    OfferingSelectionState.DISABLED to OfferingAvailability.UNAVAILABLE,
                )
            val history = mutableListOf(latest(id))
            states.drop(1).forEach { (selectionState, availability) ->
                val previous = history.last()
                val replacement = previous.offerings.single().copy(selectionState = selectionState, availability = availability)
                UpdateOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    previous.revision,
                    listOf(
                        Offering(
                            horchata,
                            flavors,
                            replacement.displayName,
                            selectionState = replacement.selectionState,
                            availability = replacement.availability,
                        ),
                    ),
                )
                val current = latest(id)
                current.previousRevision shouldBe previous.revision
                current.revision shouldBe previous.revision.next()
                current.offerings.shouldContainExactly(replacement)
                history += current
            }
            history.map { it.offerings.single().selectionState to it.offerings.single().availability }.shouldContainExactly(states)
            val both = history.last()
            shouldThrow<CommerceFailure.Conflict> {
                UpdateOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    history.first().revision,
                    listOf(
                        Offering(
                            horchata,
                            flavors,
                            "Stale",
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
            }
            latest(id) shouldBe both
            RetireOfferings(transactor, repository)(id, both.revision, listOf(horchata))
            val retired = latest(id)
            retired.offerings shouldBe emptyList()
            ListRetiredOfferings(
                transactor,
                repository,
            )(id).value.shouldContainExactly(CatalogResult(both.reference, both.offerings.single()))
            RestoreOfferings(
                transactor,
                repository,
            )(
                id,
                retired.revision,
                listOf(
                    Offering(
                        horchata,
                        flavors,
                        "Horchata",
                        selectionState = OfferingSelectionState.DISABLED,
                        availability = OfferingAvailability.UNAVAILABLE,
                    ),
                ),
            )
            latest(id).offerings.single() shouldBe both.offerings.single()
        }

        test("an update is a full replacement, so unrelated changes keep both properties only when the caller supplies them") {
            val id = catalog()
            val both =
                Offering(
                    horchata,
                    flavors,
                    "Horchata",
                    selectionState = OfferingSelectionState.DISABLED,
                    availability = OfferingAvailability.UNAVAILABLE,
                )
            AddOfferings(transactor, repository)(id, observedRevision(id), listOf(both))
            val before = latest(id)
            UpdateOfferings(
                transactor,
                repository,
            )(
                id,
                before.revision,
                listOf(Offering(horchata, flavors, "Updated name", selectionState = both.selectionState, availability = both.availability)),
            )
            latest(id).offerings.single() shouldBe both.copy(displayName = "Updated name")
            latest(id).previousRevision shouldBe before.revision
        }

        test("each batch adds, updates, retires, or restores several offerings in exactly one successor revision") {
            val id = catalog()
            val (a, b, c) =
                listOf("a", "b", "c").map {
                    Offering(
                        OfferingKey(it),
                        flavors,
                        it.uppercase(),
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    )
                }
            val start = observedRevision(id)

            AddOfferings(transactor, repository)(id, start, listOf(a, b, c)).let {
                it.reference.revision shouldBe start.next()
                it.value.shouldContainExactly(a, b, c)
            }
            latest(id).offerings.shouldContainExactly(a, b, c)

            val renamedC = c.copy(displayName = "C2")
            val renamedA = a.copy(displayName = "A2", price = price("1.00"))
            // Update order in the batch does not move offerings: each keeps its position.
            UpdateOfferings(transactor, repository)(id, observedRevision(id), listOf(renamedC, renamedA))
            latest(id).offerings.shouldContainExactly(renamedA, b, renamedC)
            latest(id).revision shouldBe start.next().next()

            RetireOfferings(transactor, repository)(id, observedRevision(id), listOf(OfferingKey("c"), OfferingKey("a")))
            val retired = latest(id)
            retired.offerings.shouldContainExactly(b)
            ListRetiredOfferings(transactor, repository)(id).value.map { it.value.key.value to it.reference.revision }.shouldContainExactly(
                "a" to start.next().next(),
                "c" to start.next().next(),
            )

            // Restoration appends in batch order.
            RestoreOfferings(transactor, repository)(id, observedRevision(id), listOf(c, a))
            latest(id).offerings.shouldContainExactly(b, c, a)
            latest(id).revision.number shouldBe start.number + 4
        }

        test("a batch is all or nothing: one invalid item saves no item and no revision") {
            val id = catalog()
            val existing =
                Offering(
                    OfferingKey("existing"),
                    flavors,
                    "Existing",
                    selectionState = OfferingSelectionState.ENABLED,
                    availability = OfferingAvailability.AVAILABLE,
                )
            AddOfferings(transactor, repository)(id, observedRevision(id), listOf(existing))
            RetireOfferings(transactor, repository)(id, observedRevision(id), listOf(existing.key))
            AddOfferings(transactor, repository)(id, observedRevision(id), listOf(existing.copy(key = OfferingKey("active"))))
            val before = latest(id)
            val fresh =
                Offering(
                    OfferingKey("fresh"),
                    flavors,
                    "Fresh",
                    selectionState = OfferingSelectionState.ENABLED,
                    availability = OfferingAvailability.AVAILABLE,
                )
            val missing =
                Offering(
                    OfferingKey("missing"),
                    flavors,
                    "Missing",
                    selectionState = OfferingSelectionState.ENABLED,
                    availability = OfferingAvailability.AVAILABLE,
                )
            val active = before.offerings.single()

            shouldThrow<CommerceFailure.Conflict> { AddOfferings(transactor, repository)(id, before.revision, listOf(fresh, active)) }
            shouldThrow<CommerceFailure.Conflict> { AddOfferings(transactor, repository)(id, before.revision, listOf(fresh, existing)) }
            shouldThrow<CommerceFailure.NotFound> {
                AddOfferings(
                    transactor,
                    repository,
                )(id, before.revision, listOf(fresh, fresh.copy(key = OfferingKey("x"), category = OfferingCategoryKey("absent"))))
            }
            shouldThrow<CommerceFailure.NotFound> {
                UpdateOfferings(transactor, repository)(id, before.revision, listOf(active.copy(displayName = "Changed"), missing))
            }
            shouldThrow<CommerceFailure.Conflict> {
                UpdateOfferings(transactor, repository)(id, before.revision, listOf(active.copy(displayName = "Changed"), existing))
            }
            shouldThrow<CommerceFailure.NotFound> {
                RetireOfferings(transactor, repository)(id, before.revision, listOf(active.key, missing.key))
            }
            shouldThrow<CommerceFailure.Conflict> {
                RestoreOfferings(transactor, repository)(id, before.revision, listOf(existing, active))
            }
            shouldThrow<CommerceFailure.NotFound> {
                RestoreOfferings(transactor, repository)(id, before.revision, listOf(existing, missing))
            }
            latest(id) shouldBe before

            // A batch names at least one offering, and each offering once.
            listOf<() -> Any>(
                { AddOfferings(transactor, repository)(id, before.revision, emptyList()) },
                { UpdateOfferings(transactor, repository)(id, before.revision, emptyList()) },
                { RetireOfferings(transactor, repository)(id, before.revision, emptyList()) },
                { RestoreOfferings(transactor, repository)(id, before.revision, emptyList()) },
                { AddOfferings(transactor, repository)(id, before.revision, listOf(fresh, fresh.copy(displayName = "Twice"))) },
                { UpdateOfferings(transactor, repository)(id, before.revision, listOf(active, active)) },
                { RetireOfferings(transactor, repository)(id, before.revision, listOf(active.key, active.key)) },
                { RestoreOfferings(transactor, repository)(id, before.revision, listOf(existing, existing)) },
            ).forEach { attempt -> shouldThrow<CommerceFailure.ValidationFailed> { attempt() } }
            latest(id) shouldBe before
        }

        test("offering keys remain reserved through retirement and restoration") {
            val id = catalog()
            val initial = latest(id)
            val offering =
                Offering(
                    horchata,
                    flavors,
                    "Horchata",
                    price = price("0.50"),
                    selectionState = OfferingSelectionState.ENABLED,
                    availability = OfferingAvailability.AVAILABLE,
                )
            AddOfferings(transactor, repository)(id, observedRevision(id), listOf(offering))
            val added = latest(id)
            UpdateOfferings(
                transactor,
                repository,
            )(
                id,
                observedRevision(id),
                listOf(
                    Offering(
                        horchata,
                        flavors,
                        "Horchata Soft Serve",
                        price = price("0.75"),
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    ),
                ),
            )
            val updated = latest(id)
            RetireOfferings(transactor, repository)(id, observedRevision(id), listOf(horchata)).revision.number shouldBe 5
            val retired = latest(id)
            shouldThrow<CommerceFailure.Conflict> {
                AddOfferings(transactor, repository)(id, observedRevision(id), listOf(offering.copy(displayName = "Unrelated")))
            }
            shouldThrow<CommerceFailure.Conflict> {
                UpdateOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    observedRevision(id),
                    listOf(
                        Offering(
                            horchata,
                            flavors,
                            "Again",
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
            }
            shouldThrow<CommerceFailure.Conflict> { RetireOfferings(transactor, repository)(id, observedRevision(id), listOf(horchata)) }
            latest(id) shouldBe retired
            ListRetiredOfferings(transactor, repository)(id).let {
                it.reference shouldBe retired.reference
                it.value.shouldContainExactly(CatalogResult(updated.reference, updated.offerings.single()))
            }
            RestoreOfferings(
                transactor,
                repository,
            )(
                id,
                observedRevision(id),
                listOf(
                    Offering(
                        horchata,
                        flavors,
                        "Restored Horchata",
                        price = price("1.00"),
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    ),
                ),
            )
            val restored = latest(id)
            restored.offerings.single().key shouldBe horchata
            restored.offerings.single().price shouldBe price("1.00")
            ListRetiredOfferings(transactor, repository)(id).value shouldBe emptyList()
            listOf(initial, added, updated, retired, restored).map { it.revision.number } shouldContainExactly listOf(2, 3, 4, 5, 6)
            latest(id) shouldBe restored
            transactor.inTransaction {
                repository.offeringKeyReserved(it, id, horchata) shouldBe true
                repository.offeringKeyReserved(it, OfferingsCatalogId(UUID.randomUUID()), horchata) shouldBe false
            }
            RetireOfferings(transactor, repository)(id, observedRevision(id), listOf(horchata))
            transactor.inTransaction {
                repository.retrieveRetiredOfferings(it, id).shouldContainExactly(
                    RetiredCatalogValue(restored.reference, restored.offerings.single()),
                )
            }
        }

        test("category keys remain reserved through retirement and restoration") {
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
            listOf(initial, updated, retired, restored).map { it.revision.number } shouldContainExactly listOf(2, 3, 4, 5)
            latest(id) shouldBe restored
            transactor.inTransaction {
                repository.categoryKeyReserved(it, id, flavors) shouldBe true
                repository.categoryKeyReserved(it, OfferingsCatalogId(UUID.randomUUID()), flavors) shouldBe false
            }
            RetireOfferingCategory(transactor, repository)(id, observedRevision(id), flavors)
            transactor.inTransaction {
                repository.retrieveRetiredCategories(it, id).shouldContainExactly(
                    RetiredCatalogValue(restored.reference, restored.categories.single()),
                )
            }
        }

        listOf("update", "retire", "restore").forEach { action ->
            test("$action rejects never-seen offering and category identities without advancing revision") {
                val id = catalog()
                val before = latest(id)
                shouldThrow<CommerceFailure.NotFound> {
                    when (action) {
                        "update" ->
                            UpdateOfferings(
                                transactor,
                                repository,
                            )(
                                id,
                                observedRevision(id),
                                listOf(
                                    Offering(
                                        horchata,
                                        flavors,
                                        "Unknown",
                                        selectionState = OfferingSelectionState.ENABLED,
                                        availability = OfferingAvailability.AVAILABLE,
                                    ),
                                ),
                            )
                        "retire" -> RetireOfferings(transactor, repository)(id, observedRevision(id), listOf(horchata))
                        else ->
                            RestoreOfferings(
                                transactor,
                                repository,
                            )(
                                id,
                                observedRevision(id),
                                listOf(
                                    Offering(
                                        horchata,
                                        flavors,
                                        "Unknown",
                                        selectionState = OfferingSelectionState.ENABLED,
                                        availability = OfferingAvailability.AVAILABLE,
                                    ),
                                ),
                            )
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
            val offering =
                Offering(
                    horchata,
                    flavors,
                    "Horchata",
                    selectionState = OfferingSelectionState.ENABLED,
                    availability = OfferingAvailability.AVAILABLE,
                )
            AddOfferings(transactor, repository)(id, observedRevision(id), listOf(offering))
            val before = latest(id)
            shouldThrow<CommerceFailure.Conflict> { AddOfferings(transactor, repository)(id, observedRevision(id), listOf(offering)) }
            shouldThrow<CommerceFailure.Conflict> {
                AddOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategory(flavors, "Flavors"))
            }
            shouldThrow<CommerceFailure.Conflict> {
                RestoreOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    observedRevision(id),
                    listOf(
                        Offering(
                            horchata,
                            flavors,
                            "Horchata",
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
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
                UpdateOfferings(
                    transactor,
                    repository,
                )(
                    missing,
                    observedRevision(missing),
                    listOf(
                        Offering(
                            horchata,
                            flavors,
                            "Horchata",
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
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
                AddOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    observedRevision(id),
                    listOf(
                        Offering(
                            OfferingKey(it),
                            flavors,
                            it,
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
            }
            UpdateOfferings(
                transactor,
                repository,
            )(
                id,
                observedRevision(id),
                listOf(
                    Offering(
                        horchata,
                        flavors,
                        "Changed",
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    ),
                ),
            )
            UpdateOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategoryKey("second"), "Changed")
            latest(id).offerings.map { it.key.value }.shouldContainExactly("vanilla", "horchata", "chocolate")
            latest(id).categories.map { it.key.value }.shouldContainExactly("flavors", "second", "third")
            RetireOfferings(transactor, repository)(id, observedRevision(id), listOf(horchata))
            RetireOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategoryKey("second"))
            latest(id).offerings.map { it.key.value }.shouldContainExactly("vanilla", "chocolate")
            latest(id).categories.map { it.key.value }.shouldContainExactly("flavors", "third")
            RestoreOfferings(
                transactor,
                repository,
            )(
                id,
                observedRevision(id),
                listOf(
                    Offering(
                        horchata,
                        flavors,
                        "Restored",
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    ),
                ),
            )
            RestoreOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategoryKey("second"), "Restored")
            AddOfferings(
                transactor,
                repository,
            )(
                id,
                observedRevision(id),
                listOf(
                    Offering(
                        OfferingKey("last"),
                        flavors,
                        "Last",
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    ),
                ),
            )
            AddOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategory(OfferingCategoryKey("last"), "Last"))
            latest(id).offerings.map { it.key.value }.shouldContainExactly("vanilla", "chocolate", "horchata", "last")
            latest(id).categories.map { it.key.value }.shouldContainExactly("flavors", "third", "second", "last")
        }

        test("category retirement never cascades and offering moves and restores require a current category") {
            val id = catalog()
            AddOfferings(
                transactor,
                repository,
            )(
                id,
                observedRevision(id),
                listOf(
                    Offering(
                        horchata,
                        flavors,
                        "Horchata",
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    ),
                ),
            )
            val original = latest(id)
            shouldThrow<CommerceFailure.Conflict> { RetireOfferingCategory(transactor, repository)(id, observedRevision(id), flavors) }
            val missing = OfferingCategoryKey("missing")
            shouldThrow<CommerceFailure.NotFound> {
                UpdateOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    observedRevision(id),
                    listOf(
                        Offering(
                            horchata,
                            missing,
                            "Horchata",
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
            }
            val target = OfferingCategoryKey("target")
            AddOfferingCategory(transactor, repository)(id, observedRevision(id), OfferingCategory(target, "Target"))
            RetireOfferingCategory(transactor, repository)(id, observedRevision(id), target)
            shouldThrow<CommerceFailure.NotFound> {
                UpdateOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    observedRevision(id),
                    listOf(
                        Offering(
                            horchata,
                            target,
                            "Horchata",
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
            }
            RetireOfferings(transactor, repository)(id, observedRevision(id), listOf(horchata))
            shouldThrow<CommerceFailure.NotFound> {
                RestoreOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    observedRevision(id),
                    listOf(
                        Offering(
                            horchata,
                            missing,
                            "Horchata",
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
            }
            shouldThrow<CommerceFailure.NotFound> {
                RestoreOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    observedRevision(id),
                    listOf(
                        Offering(
                            horchata,
                            target,
                            "Horchata",
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
            }
            RetireOfferingCategory(transactor, repository)(id, observedRevision(id), flavors)
            RestoreOfferingCategory(transactor, repository)(id, observedRevision(id), target, "Target")
            RestoreOfferings(
                transactor,
                repository,
            )(
                id,
                observedRevision(id),
                listOf(
                    Offering(
                        horchata,
                        target,
                        "Horchata",
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    ),
                ),
            )
            RestoreOfferingCategory(transactor, repository)(id, observedRevision(id), flavors, "Flavors")
            UpdateOfferings(
                transactor,
                repository,
            )(
                id,
                observedRevision(id),
                listOf(
                    Offering(
                        horchata,
                        flavors,
                        "Horchata",
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    ),
                ),
            )
            RetireOfferingCategory(transactor, repository)(id, observedRevision(id), target)
        }

        test("retired discovery is catalog scoped, ordered by key, and returns each last complete representation") {
            val id = catalog()
            val other = catalog()
            listOf("z", "a").forEach {
                AddOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    observedRevision(id),
                    listOf(
                        Offering(
                            OfferingKey(it),
                            flavors,
                            it,
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
            }
            UpdateOfferings(
                transactor,
                repository,
            )(
                id,
                observedRevision(id),
                listOf(
                    Offering(
                        OfferingKey("a"),
                        flavors,
                        "Last A",
                        "Description",
                        price("2.5000"),
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    ),
                ),
            )
            val lastA = latest(id)
            RetireOfferings(transactor, repository)(id, observedRevision(id), listOf(OfferingKey("a")))
            val lastZ = latest(id)
            RetireOfferings(transactor, repository)(id, observedRevision(id), listOf(OfferingKey("z")))
            AddOfferings(
                transactor,
                repository,
            )(
                other,
                observedRevision(other),
                listOf(
                    Offering(
                        OfferingKey("a"),
                        flavors,
                        "Other catalog",
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    ),
                ),
            )
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
            // A blank display name never reaches an operation: Offering itself rejects it.
            shouldThrow<IllegalArgumentException> {
                Offering(
                    horchata,
                    flavors,
                    " ",
                    selectionState = OfferingSelectionState.ENABLED,
                    availability = OfferingAvailability.AVAILABLE,
                )
            }
            val id = catalog()
            AddOfferings(
                transactor,
                repository,
            )(
                id,
                observedRevision(id),
                listOf(
                    Offering(
                        horchata,
                        flavors,
                        "Horchata",
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    ),
                ),
            )
            val before = latest(id)
            shouldThrow<CommerceFailure.ValidationFailed> {
                UpdateOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    observedRevision(id),
                    emptyList(),
                )
            }
            shouldThrow<CommerceFailure.ValidationFailed> {
                UpdateOfferingCategory(transactor, repository)(id, observedRevision(id), flavors, "Flavors", minimumSelections = -1)
            }
            latest(id) shouldBe before
            RetireOfferings(transactor, repository)(id, observedRevision(id), listOf(horchata))
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
                RestoreOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    observedRevision(id),
                    listOf(
                        Offering(
                            horchata,
                            flavors,
                            "Horchata",
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                        Offering(
                            horchata,
                            flavors,
                            "Again",
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
            }
            latest(id) shouldBe restoredCategory
        }

        test("a stale replacement cannot undo a committed price update or create another revision") {
            val id = catalog()
            AddOfferings(
                transactor,
                repository,
            )(
                id,
                observedRevision(id),
                listOf(
                    Offering(
                        horchata,
                        flavors,
                        "Horchata",
                        price = price("0.50"),
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    ),
                ),
            )
            val uiObserved = latest(id)
            UpdateOfferings(
                transactor,
                repository,
            )(
                id,
                uiObserved.revision,
                listOf(
                    Offering(
                        horchata,
                        flavors,
                        "Horchata",
                        price = price("0.75"),
                        selectionState = OfferingSelectionState.ENABLED,
                        availability = OfferingAvailability.AVAILABLE,
                    ),
                ),
            )
            val committed = latest(id)
            shouldThrow<CommerceFailure.Conflict> {
                UpdateOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    uiObserved.revision,
                    listOf(
                        Offering(
                            horchata,
                            flavors,
                            "New display name",
                            price = price("0.50"),
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
            }
            latest(id) shouldBe committed
            latest(id).offerings.single().price shouldBe price("0.75")
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
                AddOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    observedRevision(id),
                    listOf(
                        Offering(
                            horchata,
                            flavors,
                            "Horchata",
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
                if (action in setOf("restoreOffering", "retireCategory", "restoreCategory")) {
                    RetireOfferings(transactor, repository)(id, observedRevision(id), listOf(horchata))
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
                            AddOfferings(
                                transactor,
                                repository,
                            )(
                                id,
                                uiObserved.revision,
                                listOf(
                                    Offering(
                                        OfferingKey("new"),
                                        flavors,
                                        "New",
                                        selectionState = OfferingSelectionState.ENABLED,
                                        availability = OfferingAvailability.AVAILABLE,
                                    ),
                                ),
                            )
                        "addCategory" ->
                            AddOfferingCategory(
                                transactor,
                                repository,
                            )(id, uiObserved.revision, OfferingCategory(OfferingCategoryKey("new"), "New"))
                        "updateOffering" ->
                            UpdateOfferings(
                                transactor,
                                repository,
                            )(
                                id,
                                uiObserved.revision,
                                listOf(
                                    Offering(
                                        horchata,
                                        flavors,
                                        "New",
                                        selectionState = OfferingSelectionState.ENABLED,
                                        availability = OfferingAvailability.AVAILABLE,
                                    ),
                                ),
                            )
                        "retireOffering" -> RetireOfferings(transactor, repository)(id, uiObserved.revision, listOf(horchata))
                        "restoreOffering" ->
                            RestoreOfferings(
                                transactor,
                                repository,
                            )(
                                id,
                                uiObserved.revision,
                                listOf(
                                    Offering(
                                        horchata,
                                        flavors,
                                        "New",
                                        selectionState = OfferingSelectionState.ENABLED,
                                        availability = OfferingAvailability.AVAILABLE,
                                    ),
                                ),
                            )
                        "updateCategory" -> UpdateOfferingCategory(transactor, repository)(id, uiObserved.revision, flavors, "New")
                        "retireCategory" -> RetireOfferingCategory(transactor, repository)(id, uiObserved.revision, flavors)
                        else -> RestoreOfferingCategory(transactor, repository)(id, uiObserved.revision, flavors, "New")
                    }
                }
                latest(id) shouldBe committed
            }
        }

        listOf("update", "retire", "restore").forEach { action ->
            test("competing $action writers from one revision produce one successor and one conflict") {
                val id = catalog()
                AddOfferings(
                    transactor,
                    repository,
                )(
                    id,
                    observedRevision(id),
                    listOf(
                        Offering(
                            horchata,
                            flavors,
                            "Horchata",
                            selectionState = OfferingSelectionState.ENABLED,
                            availability = OfferingAvailability.AVAILABLE,
                        ),
                    ),
                )
                if (action == "restore") RetireOfferings(transactor, repository)(id, observedRevision(id), listOf(horchata))
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
                                                UpdateOfferings(
                                                    transactor,
                                                    synchronizedReads,
                                                )(
                                                    id,
                                                    before.revision,
                                                    listOf(
                                                        Offering(
                                                            horchata,
                                                            flavors,
                                                            "Writer $writer",
                                                            selectionState = OfferingSelectionState.ENABLED,
                                                            availability = OfferingAvailability.AVAILABLE,
                                                        ),
                                                    ),
                                                )
                                            "retire" ->
                                                RetireOfferings(
                                                    transactor,
                                                    synchronizedReads,
                                                )(id, before.revision, listOf(horchata))
                                            else ->
                                                RestoreOfferings(
                                                    transactor,
                                                    synchronizedReads,
                                                )(
                                                    id,
                                                    before.revision,
                                                    listOf(
                                                        Offering(
                                                            horchata,
                                                            flavors,
                                                            "Writer $writer",
                                                            selectionState = OfferingSelectionState.ENABLED,
                                                            availability = OfferingAvailability.AVAILABLE,
                                                        ),
                                                    ),
                                                )
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
                latest(id).previousRevision shouldBe before.revision
            }
        }
    })
