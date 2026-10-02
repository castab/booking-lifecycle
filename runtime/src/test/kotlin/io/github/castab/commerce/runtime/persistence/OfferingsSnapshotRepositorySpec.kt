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
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.testApplicationMigrations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Duration
import java.util.Currency
import java.util.UUID

private val usd = Currency.getInstance("USD")
private val eur = Currency.getInstance("EUR")

private fun money(
    amount: String,
    currency: Currency = usd,
) = Money(BigDecimal(amount), currency)

private fun category(
    key: String,
    description: String? = null,
) = OfferingCategory(OfferingCategoryKey(key), key, description, minimumSelections = 0, maximumSelections = 3)

private fun offering(
    key: String,
    category: String,
    description: String? = null,
    price: OfferingPrice? = null,
) = Offering(OfferingKey(key), OfferingCategoryKey(category), key, description, price)

private fun catalogId() = OfferingsCatalogId(UUID.randomUUID())

private class RollBack : RuntimeException()

class OfferingsSnapshotRepositorySpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var dataSource: com.zaxxer.hikari.HikariDataSource
        lateinit var transactor: Transactor
        val repository: OfferingsSnapshotRepository = PostgresOfferingsSnapshotRepository()

        beforeSpec {
            database = TestDatabase.create()
            dataSource = createDataSource(database.configuration, "offerings-repository-spec")
            MigrationLifecycle(dataSource, testApplicationMigrations()).migrate()
            transactor = Transactor(Jdbi.create(dataSource))
        }

        afterSpec {
            dataSource.close()
            database.close()
        }

        fun outsideCount(
            sql: String,
            reference: OfferingsSnapshotReference? = null,
        ): Int =
            DriverManager
                .getConnection(
                    database.configuration.jdbcUrl,
                    database.configuration.username,
                    database.configuration.password,
                ).use { connection ->
                    connection.prepareStatement(sql).use { statement ->
                        reference?.let {
                            statement.setObject(1, it.catalogId.value)
                            statement.setInt(2, it.revision.number)
                        }
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                    }
                }

        fun applicationRows(): Int = outsideCount("SELECT count(*) FROM testapp.test_application_records")

        fun snapshotRows(reference: OfferingsSnapshotReference): Int =
            outsideCount(
                "SELECT count(*) FROM commerce.offerings_snapshots WHERE catalog_id = ? AND revision = ?",
                reference,
            )

        test("selection state and availability round trip all four combinations") {
            val first =
                OfferingsSnapshot.create(
                    catalogId(),
                    listOf(category("choice")),
                    listOf(
                        offering("normal", "choice"),
                        offering("disabled", "choice").copy(selectionState = OfferingSelectionState.DISABLED),
                        offering("unavailable", "choice").copy(availability = OfferingAvailability.UNAVAILABLE),
                        offering("both", "choice").copy(
                            selectionState = OfferingSelectionState.DISABLED,
                            availability = OfferingAvailability.UNAVAILABLE,
                        ),
                    ),
                )
            transactor.inTransaction { repository.insert(it, first) }
            transactor.inTransaction { repository.retrieveVersion(it, first.reference) } shouldBe first
        }

        test("null, unknown, and missing stored selection values fail loudly instead of defaulting") {
            val first = OfferingsSnapshot.create(catalogId(), listOf(category("choice")), listOf(offering("item", "choice")))
            transactor.inTransaction { repository.insert(it, first) }
            listOf("selectionState", "availability").forEach { property ->
                listOf(
                    "jsonb_set(catalog, '{offerings,0,$property}', 'null'::jsonb)",
                    "jsonb_set(catalog, '{offerings,0,$property}', '\"OTHER\"'::jsonb)",
                    "jsonb_set(catalog, '{offerings,0,$property}', '\"enabled\"'::jsonb)",
                    "catalog #- '{offerings,0,$property}'",
                ).forEach { corruption ->
                    shouldThrow<IllegalStateException> {
                        transactor.inTransaction { transaction ->
                            transaction.handle
                                .createUpdate("UPDATE commerce.offerings_snapshots SET catalog = $corruption WHERE catalog_id = :id")
                                .bind("id", first.catalogId.value)
                                .execute()
                            repository.retrieveVersion(transaction, first.reference)
                        }
                    }.message shouldContain "Malformed persisted offerings catalog"
                    // PostgreSQL rolls the corruption back with the failed transaction.
                    transactor.inTransaction { repository.retrieveVersion(it, first.reference) } shouldBe first
                }
            }
        }

        test("revisions round trip with order, nullable fields, exact prices, and isolated catalog histories") {
            val first =
                OfferingsSnapshot.create(
                    catalogId(),
                    listOf(category("second", "shown first"), category("first")),
                    listOf(
                        offering("none", "second"),
                        offering("fixed", "first", "a fixed price", OfferingPrice.Fixed(money("120.00")))
                            .copy(badge = "Popular", statusNote = "Back this fall"),
                        offering("quantity", "first", price = OfferingPrice.PerQuantity(money("0.7500", eur), QuantityDimension("item"))),
                        offering(
                            "duration",
                            "second",
                            price = OfferingPrice.PerDuration(money("50.125"), Duration.ofSeconds(3600, 123456789)),
                        ),
                    ),
                )
            transactor.inTransaction { repository.insert(it, first) }
            val restored = transactor.inTransaction { repository.retrieveVersion(it, first.reference) }
            restored shouldBe first
            restored!!.categories.map { it.key.value } shouldContainExactly listOf("second", "first")
            restored.offerings.map { it.key.value } shouldContainExactly listOf("none", "fixed", "quantity", "duration")
            restored.offerings[0].description shouldBe null
            restored.offerings[0].price shouldBe null
            restored.offerings[2].price shouldBe OfferingPrice.PerQuantity(money("0.7500", eur), QuantityDimension("item"))
            restored.offerings[3].price shouldBe OfferingPrice.PerDuration(money("50.125"), Duration.ofSeconds(3600, 123456789))

            val second = first.revise(first.categories, first.offerings.reversed())
            transactor.inTransaction { repository.insert(it, second) }
            transactor.inTransaction { repository.retrieveLatestVersion(it, first.catalogId) } shouldBe second
            transactor.inTransaction { repository.retrieveVersion(it, first.reference) } shouldBe first

            val another = OfferingsSnapshot.create(catalogId(), listOf(category("other")), emptyList())
            transactor.inTransaction { repository.insert(it, another) }
            transactor.inTransaction { repository.retrieveLatestVersion(it, another.catalogId) } shouldBe another
            transactor
                .inTransaction {
                    repository.retrieveVersion(it, OfferingsSnapshotReference(first.catalogId, OfferingsRevision.of(3)))
                }.shouldBeNull()
            transactor.inTransaction { repository.retrieveLatestVersion(it, catalogId()) }.shouldBeNull()
        }

        test("duplicate revision conflicts and a missing predecessor is rejected by PostgreSQL") {
            val first = OfferingsSnapshot.create(catalogId())
            transactor.inTransaction { repository.insert(it, first) }
            shouldThrow<CommerceFailure.Conflict> { transactor.inTransaction { repository.insert(it, first) } }
            val absentPredecessor =
                OfferingsSnapshot.restore(
                    catalogId(),
                    OfferingsRevision.of(2),
                    OfferingsRevision.INITIAL,
                    emptyList(),
                    emptyList(),
                )
            shouldThrow<UnableToExecuteStatementException> {
                transactor.inTransaction { repository.insert(it, absentPredecessor) }
            }
        }

        fun storedCatalog(reference: OfferingsSnapshotReference): JsonElement =
            transactor.inTransaction { transaction ->
                Json.parseToJsonElement(
                    transaction.handle
                        .createQuery(
                            "SELECT catalog::text FROM commerce.offerings_snapshots WHERE catalog_id = :id AND revision = :revision",
                        ).bind("id", reference.catalogId.value)
                        .bind("revision", reference.revision.number)
                        .mapTo(String::class.java)
                        .one(),
                )
            }

        test("one row stores the whole revision in an explicit, stable representation") {
            val first =
                OfferingsSnapshot.create(
                    catalogId(),
                    listOf(category("second", "shown first"), category("first").copy(minimumSelections = 1, maximumSelections = null)),
                    listOf(
                        offering("none", "second"),
                        offering("fixed", "first", "a fixed price", OfferingPrice.Fixed(money("120.00")))
                            .copy(badge = "Popular", statusNote = "Back this fall"),
                        offering("quantity", "first", price = OfferingPrice.PerQuantity(money("0.7500", eur), QuantityDimension("item")))
                            .copy(selectionState = OfferingSelectionState.DISABLED),
                        offering(
                            "duration",
                            "second",
                            price = OfferingPrice.PerDuration(money("50.125"), Duration.ofSeconds(3600, 123456789)),
                        ).copy(availability = OfferingAvailability.UNAVAILABLE),
                    ),
                )
            transactor.inTransaction { repository.insert(it, first) }

            // jsonb does not preserve key order, so the comparison is on the parsed document.
            storedCatalog(first.reference) shouldBe
                Json.parseToJsonElement(
                    """{"categories": [
                        {"key": "second", "displayName": "second", "description": "shown first",
                         "minimumSelections": 0, "maximumSelections": 3},
                        {"key": "first", "displayName": "first", "description": null,
                         "minimumSelections": 1, "maximumSelections": null}],
                       "offerings": [
                        {"key": "none", "category": "second", "displayName": "none", "description": null, "price": null,
                         "selectionState": "ENABLED", "availability": "AVAILABLE", "badge": null, "statusNote": null},
                        {"key": "fixed", "category": "first", "displayName": "fixed", "description": "a fixed price",
                         "price": {"kind": "FIXED", "amount": "120.00", "currency": "USD"},
                         "selectionState": "ENABLED", "availability": "AVAILABLE",
                         "badge": "Popular", "statusNote": "Back this fall"},
                        {"key": "quantity", "category": "first", "displayName": "quantity", "description": null,
                         "price": {"kind": "PER_QUANTITY", "amount": "0.7500", "currency": "EUR", "dimension": "item"},
                         "selectionState": "DISABLED", "availability": "AVAILABLE", "badge": null, "statusNote": null},
                        {"key": "duration", "category": "second", "displayName": "duration", "description": null,
                         "price": {"kind": "PER_DURATION", "amount": "50.125", "currency": "USD",
                                   "seconds": 3600, "nanos": 123456789},
                         "selectionState": "ENABLED", "availability": "UNAVAILABLE", "badge": null, "statusNote": null}]}""",
                )
            transactor.inTransaction { repository.retrieveVersion(it, first.reference) } shouldBe first
        }

        test("a malformed stored catalog fails loudly and never defaults") {
            val first =
                OfferingsSnapshot.create(
                    catalogId(),
                    listOf(category("choice"), category("other")),
                    listOf(
                        offering("item", "choice", price = OfferingPrice.Fixed(money("1.00"))),
                        offering("timed", "choice", price = OfferingPrice.PerDuration(money("2.00"), Duration.ofMinutes(5))),
                    ),
                )
            transactor.inTransaction { repository.insert(it, first) }
            val price = "{offerings,0,price}"
            listOf(
                // wrong shape
                "catalog || '{\"unexpected\": 1}'::jsonb",
                "catalog #- '{offerings,0,description}'",
                "catalog #- '{offerings,0,badge}'",
                "catalog #- '{offerings,0,statusNote}'",
                "jsonb_set(catalog, '{offerings,0,badge}', '1'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,unexpected}', '1'::jsonb)",
                "jsonb_set(catalog, '{categories,0,minimumSelections}', '\"0\"'::jsonb)",
                "jsonb_set(catalog, '{categories,0,minimumSelections}', 'null'::jsonb)",
                // unsupported price variants, and values that are not exact decimals or currencies
                "jsonb_set(catalog, '$price', '{\"kind\": \"PER_GUEST\", \"amount\": \"1.00\", \"currency\": \"USD\"}'::jsonb)",
                "jsonb_set(catalog, '$price', '{\"amount\": \"1.00\", \"currency\": \"USD\"}'::jsonb)",
                "jsonb_set(catalog, '$price', '{\"kind\": \"FIXED\", \"amount\": 1.00, \"currency\": \"USD\"}'::jsonb)",
                "jsonb_set(catalog, '$price', '{\"kind\": \"FIXED\", \"amount\": \"1e2\", \"currency\": \"USD\"}'::jsonb)",
                "jsonb_set(catalog, '$price', '{\"kind\": \"FIXED\", \"amount\": \"1.00\", \"currency\": \"usd\"}'::jsonb)",
                "jsonb_set(catalog, '$price', '{\"kind\": \"FIXED\", \"amount\": \"1.00\", \"currency\": \"ZZZ\"}'::jsonb)",
                "jsonb_set(catalog, '$price', '{\"kind\": \"FIXED\", \"amount\": \"1.00\", \"currency\": \"USD\", \"dimension\": \"x\"}'::jsonb)",
                "jsonb_set(catalog, '{offerings,1,price,nanos}', '1000000000'::jsonb)",
                "jsonb_set(catalog, '{offerings,1,price,seconds}', '0'::jsonb) #- '{offerings,1,price,nanos}'",
                // each value is well formed but a domain invariant is broken
                "jsonb_set(catalog, '{offerings,0,key}', '\"has space\"'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,displayName}', '\" \"'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,badge}', '\" \"'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,statusNote}', '\"\"'::jsonb)",
                "jsonb_set(catalog, '{offerings,1,key}', '\"item\"'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,category}', '\"absent\"'::jsonb)",
                "jsonb_set(catalog, '{categories,1,key}', '\"choice\"'::jsonb)",
                "jsonb_set(catalog, '{categories,0,maximumSelections}', '-1'::jsonb)",
                "jsonb_set(jsonb_set(catalog, '{offerings,1,price,seconds}', '0'::jsonb), '{offerings,1,price,nanos}', '0'::jsonb)",
            ).forEach { corruption ->
                withClue(corruption) {
                    shouldThrow<IllegalStateException> {
                        transactor.inTransaction { transaction ->
                            transaction.handle
                                .createUpdate("UPDATE commerce.offerings_snapshots SET catalog = $corruption WHERE catalog_id = :id")
                                .bind("id", first.catalogId.value)
                                .execute()
                            repository.retrieveVersion(transaction, first.reference)
                        }
                    }.message shouldContain first.catalogId.value.toString()
                }
                transactor.inTransaction { repository.retrieveVersion(it, first.reference) } shouldBe first
            }
        }

        test("the database refuses a revision without a catalog object holding both arrays") {
            val id = catalogId().value
            listOf(
                "'[]'",
                "'{}'",
                "'null'",
                "'{\"categories\": [], \"offerings\": {}}'",
                "'{\"categories\": {}, \"offerings\": []}'",
                "'{\"categories\": [], \"offerings\": null}'",
            ).forEach { catalog ->
                shouldThrow<UnableToExecuteStatementException> {
                    transactor.inTransaction { transaction ->
                        transaction.handle
                            .createUpdate(
                                "INSERT INTO commerce.offerings_snapshots (catalog_id, revision, catalog) VALUES (:id, 1, $catalog::jsonb)",
                            ).bind("id", id)
                            .execute()
                    }
                }
            }
            shouldThrow<UnableToExecuteStatementException> {
                transactor.inTransaction { transaction ->
                    transaction.handle
                        .createUpdate("INSERT INTO commerce.offerings_snapshots (catalog_id, revision) VALUES (:id, 1)")
                        .bind("id", id)
                        .execute()
                }
            }
        }

        test("history questions follow the immutable revisions of one catalog") {
            val choice = category("choice")
            val extras = category("extras")
            val r1 =
                OfferingsSnapshot.create(
                    catalogId(),
                    listOf(choice, extras),
                    listOf(offering("alpha", "choice"), offering("beta", "extras"), offering("gamma", "extras", "first gamma")),
                )
            // r2 retires beta and gamma and the extras category; r3 restores gamma with a different representation.
            val r2 = r1.revise(listOf(choice), listOf(offering("alpha", "choice", "edited")))
            val r3 =
                r2.revise(
                    listOf(choice, extras),
                    listOf(offering("alpha", "choice", "edited"), offering("gamma", "extras", "second gamma")),
                )
            val r4 = r3.revise(listOf(choice), listOf(offering("alpha", "choice", "edited")))
            val other = OfferingsSnapshot.create(catalogId(), listOf(category("elsewhere")), listOf(offering("delta", "elsewhere")))
            transactor.inTransaction { transaction ->
                listOf(r1, r2, r3, r4, other).forEach { repository.insert(transaction, it) }
            }

            transactor.inTransaction { transaction ->
                listOf("alpha", "beta", "gamma").forEach {
                    repository.offeringKeyExistsInHistory(transaction, r1.catalogId, OfferingKey(it)) shouldBe true
                }
                // Exact keys only: a prefix, another catalog's key, and an unused key never exist.
                listOf("alph", "alphabet", "delta", "ALPHA").forEach {
                    repository.offeringKeyExistsInHistory(transaction, r1.catalogId, OfferingKey(it)) shouldBe false
                }
                repository.offeringKeyExistsInHistory(transaction, other.catalogId, OfferingKey("delta")) shouldBe true
                repository.categoryKeyExistsInHistory(transaction, r1.catalogId, OfferingCategoryKey("extras")) shouldBe true
                repository.categoryKeyExistsInHistory(transaction, r1.catalogId, OfferingCategoryKey("elsewhere")) shouldBe false
                repository.categoryKeyExistsInHistory(transaction, other.catalogId, OfferingCategoryKey("elsewhere")) shouldBe true

                fun retiredOfferings(snapshot: OfferingsSnapshot) =
                    repository.retrieveRetiredOfferings(transaction, snapshot.reference).map {
                        Triple(it.value.key.value, it.reference.revision.number, it.value.description)
                    }

                fun retiredCategories(snapshot: OfferingsSnapshot) =
                    repository.retrieveRetiredCategories(transaction, snapshot.reference).map {
                        it.value.key.value to it.reference.revision.number
                    }
                // Retirement is relative to the requested revision, and each value keeps the revision it was last stored in.
                retiredOfferings(r1).shouldBeEmpty()
                retiredOfferings(r2).shouldContainExactly(Triple("beta", 1, null), Triple("gamma", 1, "first gamma"))
                retiredOfferings(r3).shouldContainExactly(Triple("beta", 1, null))
                retiredOfferings(r4).shouldContainExactly(Triple("beta", 1, null), Triple("gamma", 3, "second gamma"))
                retiredCategories(r1).shouldBeEmpty()
                retiredCategories(r2).shouldContainExactly("extras" to 1)
                retiredCategories(r3).shouldBeEmpty()
                retiredCategories(r4).shouldContainExactly("extras" to 3)
                repository.retrieveRetiredOfferings(transaction, other.reference).shouldBeEmpty()
                // A catalog with no stored revision has nothing to retire.
                repository
                    .retrieveRetiredOfferings(transaction, OfferingsSnapshotReference(catalogId(), OfferingsRevision.INITIAL))
                    .shouldBeEmpty()
            }
        }

        test("retired values are ordered by key bytes, not by database collation or UTF-16 code units") {
            val keys = listOf("b", "B", "a", "_", "😀", "～", "A")
            val full = OfferingsSnapshot.create(catalogId(), listOf(category("choice")), keys.map { offering(it, "choice") })
            val empty = full.revise(listOf(category("choice")), emptyList())
            transactor.inTransaction { repository.insert(it, full) }
            transactor.inTransaction { repository.insert(it, empty) }
            transactor
                .inTransaction { repository.retrieveRetiredOfferings(it, empty.reference) }
                .map { it.value.key.value }
                .shouldContainExactly("A", "B", "_", "a", "b", "～", "😀")
        }

        test("a revision that is not stored retires every historical key, as before") {
            val r1 = OfferingsSnapshot.create(catalogId(), listOf(category("choice")), listOf(offering("alpha", "choice")))
            val r2 = r1.revise(listOf(category("choice")), listOf(offering("alpha", "choice", "later")))
            transactor.inTransaction { transaction ->
                repository.insert(transaction, r1)
                repository.insert(transaction, r2)
                val absent = OfferingsSnapshotReference(r1.catalogId, OfferingsRevision.of(5))
                repository
                    .retrieveRetiredOfferings(transaction, absent)
                    .map { it.value.key.value to it.reference.revision.number }
                    .shouldContainExactly("alpha" to 2)
                repository
                    .retrieveRetiredCategories(transaction, absent)
                    .map { it.value.key.value to it.reference.revision.number }
                    .shouldContainExactly("choice" to 2)
            }
        }

        test("every history question fails loudly when a revision it consults is not a valid snapshot") {
            val choice = category("choice")
            val other = category("other")
            val r1 =
                OfferingsSnapshot.create(
                    catalogId(),
                    listOf(choice, other),
                    listOf(
                        offering("item", "choice", price = OfferingPrice.Fixed(money("1.00"))),
                        offering("timed", "other", price = OfferingPrice.PerDuration(money("2.00"), Duration.ofMinutes(5))),
                    ),
                )
            val r2 = r1.revise(listOf(choice), listOf(offering("item", "choice")))
            val r3 = r2.revise(listOf(choice), listOf(offering("item", "choice")))
            transactor.inTransaction { transaction -> listOf(r1, r2, r3).forEach { repository.insert(transaction, it) } }

            val questions =
                mapOf<String, (Transaction) -> Any?>(
                    "offering key exists" to { repository.offeringKeyExistsInHistory(it, r1.catalogId, OfferingKey("item")) },
                    "category key exists" to { repository.categoryKeyExistsInHistory(it, r1.catalogId, OfferingCategoryKey("choice")) },
                    "retired offerings" to { repository.retrieveRetiredOfferings(it, r3.reference) },
                    "retired categories" to { repository.retrieveRetiredCategories(it, r3.reference) },
                )
            // Each corruption is valid JSON for its own property but not part of a valid snapshot or a supported representation.
            listOf(
                "jsonb_set(catalog, '{offerings,0,category}', '\"absent\"'::jsonb)",
                "jsonb_set(catalog, '{offerings,1,key}', catalog #> '{offerings,0,key}')",
                "jsonb_set(catalog, '{categories,1,key}', catalog #> '{categories,0,key}')",
                "jsonb_set(catalog, '{offerings,0,selectionState}', '\"OTHER\"'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,availability}', 'null'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,price}', '{\"kind\": \"PER_GUEST\", \"amount\": \"1.00\", \"currency\": \"USD\"}'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,price,amount}', '1.00'::jsonb)",
                "jsonb_set(catalog, '{categories,0,minimumSelections}', '\"0\"'::jsonb)",
                "catalog #- '{categories,0,displayName}'",
            ).forEach { corruption ->
                questions.forEach { (name, ask) ->
                    withClue("$name with $corruption") {
                        shouldThrow<IllegalStateException> {
                            transactor.inTransaction { transaction ->
                                transaction.handle
                                    .createUpdate(
                                        "UPDATE commerce.offerings_snapshots SET catalog = $corruption WHERE catalog_id = :id AND revision = 1",
                                    ).bind("id", r1.catalogId.value)
                                    .execute()
                                ask(transaction)
                            }
                        }.message shouldContain r1.catalogId.value.toString()
                    }
                }
            }

            // A corrupt revision after the one asked about is outside a bounded question, but not the whole-history ones.
            shouldThrow<RollBack> {
                transactor.inTransaction { transaction ->
                    transaction.handle
                        .createUpdate(
                            "UPDATE commerce.offerings_snapshots " +
                                "SET catalog = jsonb_set(catalog, '{offerings,0,category}', '\"absent\"'::jsonb) " +
                                "WHERE catalog_id = :id AND revision = 3",
                        ).bind("id", r1.catalogId.value)
                        .execute()
                    repository.retrieveRetiredOfferings(transaction, r2.reference).map { it.value.key.value } shouldContainExactly
                        listOf("timed")
                    repository.retrieveRetiredCategories(transaction, r2.reference).map { it.value.key.value } shouldContainExactly
                        listOf("other")
                    shouldThrow<IllegalStateException> {
                        repository.offeringKeyExistsInHistory(transaction, r1.catalogId, OfferingKey("item"))
                    }
                    shouldThrow<IllegalStateException> {
                        repository.categoryKeyExistsInHistory(transaction, r1.catalogId, OfferingCategoryKey("choice"))
                    }
                    shouldThrow<IllegalStateException> { repository.retrieveRetiredOfferings(transaction, r3.reference) }
                    // Roll back the corruption so the stored revisions stay as inserted.
                    throw RollBack()
                }
            }
        }

        test("one caller transaction atomically commits and rolls back commerce and application rows") {
            val rolledBack = OfferingsSnapshot.create(catalogId(), listOf(category("service")), listOf(offering("standard", "service")))
            val rowId = UUID.randomUUID()
            val before = applicationRows()
            shouldThrow<IllegalStateException> {
                transactor.inTransaction { transaction ->
                    repository.insert(transaction, rolledBack)
                    transaction.handle
                        .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, :value)")
                        .bind("id", rowId)
                        .bind("value", "rolled back")
                        .execute()
                    throw IllegalStateException("rollback both schemas")
                }
            }
            snapshotRows(rolledBack.reference) shouldBe 0
            applicationRows() shouldBe before

            val committed = OfferingsSnapshot.create(catalogId(), listOf(category("service")), listOf(offering("standard", "service")))
            transactor.inTransaction { transaction ->
                repository.insert(transaction, committed)
                transaction.handle
                    .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, :value)")
                    .bind("id", rowId)
                    .bind("value", "committed")
                    .execute()
            }
            snapshotRows(committed.reference) shouldBe 1
            applicationRows() shouldBe before + 1
        }
    })
