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
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
            catalogId: OfferingsCatalogId? = null,
        ): Int =
            DriverManager
                .getConnection(
                    database.configuration.jdbcUrl,
                    database.configuration.username,
                    database.configuration.password,
                ).use { connection ->
                    connection.prepareStatement(sql).use { statement ->
                        catalogId?.let { statement.setObject(1, it.value) }
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                    }
                }

        fun applicationRows(): Int = outsideCount("SELECT count(*) FROM testapp.test_application_records")

        fun catalogRows(catalogId: OfferingsCatalogId): Int =
            outsideCount("SELECT count(*) FROM commerce.offerings_catalogs WHERE catalog_id = ?", catalogId)

        fun latest(catalogId: OfferingsCatalogId): OfferingsSnapshot? =
            transactor.inTransaction { repository.retrieveLatestVersion(it, catalogId) }

        fun corrupt(
            transaction: Transaction,
            catalogId: OfferingsCatalogId,
            corruption: String,
        ) {
            transaction.handle
                .createUpdate("UPDATE commerce.offerings_catalogs SET catalog = $corruption WHERE catalog_id = :id")
                .bind("id", catalogId.value)
                .execute()
        }

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
            transactor.inTransaction { repository.save(it, first) }
            latest(first.catalogId) shouldBe first
        }

        test("null, unknown, and missing stored selection values fail loudly instead of defaulting") {
            val first = OfferingsSnapshot.create(catalogId(), listOf(category("choice")), listOf(offering("item", "choice")))
            transactor.inTransaction { repository.save(it, first) }
            listOf("selectionState", "availability").forEach { property ->
                listOf(
                    "jsonb_set(catalog, '{offerings,0,$property}', 'null'::jsonb)",
                    "jsonb_set(catalog, '{offerings,0,$property}', '\"OTHER\"'::jsonb)",
                    "jsonb_set(catalog, '{offerings,0,$property}', '\"enabled\"'::jsonb)",
                    "catalog #- '{offerings,0,$property}'",
                ).forEach { corruption ->
                    shouldThrow<IllegalStateException> {
                        transactor.inTransaction { transaction ->
                            corrupt(transaction, first.catalogId, corruption)
                            repository.retrieveLatestVersion(transaction, first.catalogId)
                        }
                    }.message shouldContain "Malformed persisted offerings catalog"
                    // PostgreSQL rolls the corruption back with the failed transaction.
                    latest(first.catalogId) shouldBe first
                }
            }
        }

        test("a catalog keeps one row: its current revision, with order, nullable fields, and exact prices") {
            val first =
                OfferingsSnapshot.create(
                    catalogId(),
                    listOf(category("second", "shown first"), category("first")),
                    listOf(
                        offering("none", "second"),
                        offering("fixed", "first", "a fixed price", OfferingPrice.Fixed(money("120.00")))
                            .copy(badge = "Popular", statusNote = "Back this fall", infoNote = "Contains peanuts"),
                        offering("quantity", "first", price = OfferingPrice.PerQuantity(money("0.7500", eur), QuantityDimension("item"))),
                        offering(
                            "duration",
                            "second",
                            price = OfferingPrice.PerDuration(money("50.125"), Duration.ofSeconds(3600, 123456789)),
                        ),
                    ),
                )
            transactor.inTransaction { repository.save(it, first) }
            val restored = latest(first.catalogId)
            restored shouldBe first
            restored!!.categories.map { it.key.value } shouldContainExactly listOf("second", "first")
            restored.offerings.map { it.key.value } shouldContainExactly listOf("none", "fixed", "quantity", "duration")
            restored.offerings[0].description shouldBe null
            restored.offerings[0].price shouldBe null
            restored.offerings[2].price shouldBe OfferingPrice.PerQuantity(money("0.7500", eur), QuantityDimension("item"))
            restored.offerings[3].price shouldBe OfferingPrice.PerDuration(money("50.125"), Duration.ofSeconds(3600, 123456789))

            val second = first.revise(first.categories, first.offerings.reversed())
            transactor.inTransaction { repository.save(it, second) }
            latest(first.catalogId) shouldBe second
            latest(first.catalogId)!!.previousRevision shouldBe OfferingsRevision.INITIAL
            catalogRows(first.catalogId) shouldBe 1

            val another = OfferingsSnapshot.create(catalogId(), listOf(category("other")), emptyList())
            transactor.inTransaction { repository.save(it, another) }
            latest(another.catalogId) shouldBe another
            latest(first.catalogId) shouldBe second
            latest(catalogId()).shouldBeNull()
        }

        test("a save conflicts unless it creates a new catalog or succeeds the current revision") {
            val first = OfferingsSnapshot.create(catalogId(), listOf(category("choice")))
            transactor.inTransaction { repository.save(it, first) }
            shouldThrow<CommerceFailure.Conflict> { transactor.inTransaction { repository.save(it, first) } }
                .message shouldContain "already exists"
            val second = first.revise(first.categories, listOf(offering("item", "choice")))
            transactor.inTransaction { repository.save(it, second) }
            // Another successor of the replaced revision is stale.
            val stale = first.revise(emptyList(), emptyList())
            shouldThrow<CommerceFailure.Conflict> { transactor.inTransaction { repository.save(it, stale) } }
                .message shouldContain "is at r2, not r1"
            // A successor that skips ahead of the current revision is not its successor either.
            val ahead = second.revise(second.categories, emptyList()).revise(second.categories, emptyList())
            shouldThrow<CommerceFailure.Conflict> { transactor.inTransaction { repository.save(it, ahead) } }
            latest(first.catalogId) shouldBe second
            val orphan =
                OfferingsSnapshot.restore(
                    catalogId(),
                    OfferingsRevision.of(2),
                    OfferingsRevision.INITIAL,
                    emptyList(),
                    emptyList(),
                )
            shouldThrow<CommerceFailure.Conflict> { transactor.inTransaction { repository.save(it, orphan) } }
                .message shouldContain "does not exist"
            latest(orphan.catalogId).shouldBeNull()
        }

        fun storedCatalog(catalogId: OfferingsCatalogId): JsonElement =
            transactor.inTransaction { transaction ->
                Json.parseToJsonElement(
                    transaction.handle
                        .createQuery("SELECT catalog::text FROM commerce.offerings_catalogs WHERE catalog_id = :id")
                        .bind("id", catalogId.value)
                        .mapTo(String::class.java)
                        .one(),
                )
            }

        test("one row stores the current revision and its retired entries in an explicit, stable representation") {
            val first =
                OfferingsSnapshot.create(
                    catalogId(),
                    listOf(category("second", "shown first"), category("first").copy(minimumSelections = 1, maximumSelections = null)),
                    listOf(
                        offering("none", "second"),
                        offering("fixed", "first", "a fixed price", OfferingPrice.Fixed(money("120.00")))
                            .copy(badge = "Popular", statusNote = "Back this fall", infoNote = "Contains peanuts"),
                        offering("quantity", "first", price = OfferingPrice.PerQuantity(money("0.7500", eur), QuantityDimension("item")))
                            .copy(selectionState = OfferingSelectionState.DISABLED),
                        offering(
                            "duration",
                            "second",
                            price = OfferingPrice.PerDuration(money("50.125"), Duration.ofSeconds(3600, 123456789)),
                        ).copy(availability = OfferingAvailability.UNAVAILABLE),
                    ),
                )
            transactor.inTransaction { repository.save(it, first) }

            // jsonb does not preserve key order, so the comparison is on the parsed document.
            storedCatalog(first.catalogId) shouldBe
                Json.parseToJsonElement(
                    """{"categories": [
                        {"key": "second", "displayName": "second", "description": "shown first",
                         "minimumSelections": 0, "maximumSelections": 3},
                        {"key": "first", "displayName": "first", "description": null,
                         "minimumSelections": 1, "maximumSelections": null}],
                       "offerings": [
                        {"key": "none", "category": "second", "displayName": "none", "description": null, "price": null,
                         "selectionState": "ENABLED", "availability": "AVAILABLE", "badge": null, "statusNote": null, "infoNote": null},
                        {"key": "fixed", "category": "first", "displayName": "fixed", "description": "a fixed price",
                         "price": {"kind": "FIXED", "amount": "120.00", "currency": "USD"},
                         "selectionState": "ENABLED", "availability": "AVAILABLE",
                         "badge": "Popular", "statusNote": "Back this fall", "infoNote": "Contains peanuts"},
                        {"key": "quantity", "category": "first", "displayName": "quantity", "description": null,
                         "price": {"kind": "PER_QUANTITY", "amount": "0.7500", "currency": "EUR", "dimension": "item"},
                         "selectionState": "DISABLED", "availability": "AVAILABLE", "badge": null, "statusNote": null, "infoNote": null},
                        {"key": "duration", "category": "second", "displayName": "duration", "description": null,
                         "price": {"kind": "PER_DURATION", "amount": "50.125", "currency": "USD",
                                   "seconds": 3600, "nanos": 123456789},
                         "selectionState": "ENABLED", "availability": "UNAVAILABLE", "badge": null, "statusNote": null, "infoNote": null}],
                       "retiredCategories": [],
                       "retiredOfferings": []}""",
                )
            latest(first.catalogId) shouldBe first

            val second = first.revise(listOf(first.categories[1]), first.offerings.filter { it.category.value == "first" })
            transactor.inTransaction { repository.save(it, second) }
            storedCatalog(first.catalogId) shouldBe
                Json.parseToJsonElement(
                    """{"categories": [
                        {"key": "first", "displayName": "first", "description": null,
                         "minimumSelections": 1, "maximumSelections": null}],
                       "offerings": [
                        {"key": "fixed", "category": "first", "displayName": "fixed", "description": "a fixed price",
                         "price": {"kind": "FIXED", "amount": "120.00", "currency": "USD"},
                         "selectionState": "ENABLED", "availability": "AVAILABLE",
                         "badge": "Popular", "statusNote": "Back this fall", "infoNote": "Contains peanuts"},
                        {"key": "quantity", "category": "first", "displayName": "quantity", "description": null,
                         "price": {"kind": "PER_QUANTITY", "amount": "0.7500", "currency": "EUR", "dimension": "item"},
                         "selectionState": "DISABLED", "availability": "AVAILABLE", "badge": null, "statusNote": null, "infoNote": null}],
                       "retiredCategories": [
                        {"lastSeenRevision": 1,
                         "category": {"key": "second", "displayName": "second", "description": "shown first",
                                      "minimumSelections": 0, "maximumSelections": 3}}],
                       "retiredOfferings": [
                        {"lastSeenRevision": 1,
                         "offering": {"key": "none", "category": "second", "displayName": "none", "description": null,
                                      "price": null, "selectionState": "ENABLED", "availability": "AVAILABLE",
                                      "badge": null, "statusNote": null, "infoNote": null}},
                        {"lastSeenRevision": 1,
                         "offering": {"key": "duration", "category": "second", "displayName": "duration", "description": null,
                                      "price": {"kind": "PER_DURATION", "amount": "50.125", "currency": "USD",
                                                "seconds": 3600, "nanos": 123456789},
                                      "selectionState": "ENABLED", "availability": "UNAVAILABLE",
                                      "badge": null, "statusNote": null, "infoNote": null}}]}""",
                )
        }

        test("a malformed stored catalog fails loudly and never defaults") {
            val first =
                OfferingsSnapshot.create(
                    catalogId(),
                    listOf(category("choice"), category("other"), category("gone")),
                    listOf(
                        offering("item", "choice", price = OfferingPrice.Fixed(money("1.00"))),
                        offering("timed", "choice", price = OfferingPrice.PerDuration(money("2.00"), Duration.ofMinutes(5))),
                        offering("old", "gone"),
                        offering("older", "gone"),
                    ),
                )
            val second = first.revise(first.categories.take(2), first.offerings.take(2))
            transactor.inTransaction {
                repository.save(it, first)
                repository.save(it, second)
            }
            val price = "{offerings,0,price}"
            listOf(
                // wrong shape
                "catalog || '{\"unexpected\": 1}'::jsonb",
                "catalog #- '{retiredOfferings,0,offering}'",
                "catalog #- '{offerings,0,description}'",
                "catalog #- '{offerings,0,badge}'",
                "catalog #- '{offerings,0,statusNote}'",
                "catalog #- '{offerings,0,infoNote}'",
                "jsonb_set(catalog, '{offerings,0,infoNote}', 'true'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,badge}', '1'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,unexpected}', '1'::jsonb)",
                "jsonb_set(catalog, '{categories,0,minimumSelections}', '\"0\"'::jsonb)",
                "jsonb_set(catalog, '{categories,0,minimumSelections}', 'null'::jsonb)",
                "jsonb_set(catalog, '{retiredOfferings,0,lastSeenRevision}', '\"1\"'::jsonb)",
                "catalog #- '{retiredOfferings,0,lastSeenRevision}'",
                "jsonb_set(catalog, '{retiredOfferings,0,offering,availability}', 'null'::jsonb)",
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
                // each value is well formed but a domain or retirement invariant is broken
                "jsonb_set(catalog, '{offerings,0,key}', '\"has space\"'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,displayName}', '\" \"'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,badge}', '\" \"'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,statusNote}', '\"\"'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,infoNote}', '\" \"'::jsonb)",
                "jsonb_set(catalog, '{offerings,1,key}', '\"item\"'::jsonb)",
                "jsonb_set(catalog, '{offerings,0,category}', '\"absent\"'::jsonb)",
                "jsonb_set(catalog, '{categories,1,key}', '\"choice\"'::jsonb)",
                "jsonb_set(catalog, '{categories,0,maximumSelections}', '-1'::jsonb)",
                "jsonb_set(jsonb_set(catalog, '{offerings,1,price,seconds}', '0'::jsonb), '{offerings,1,price,nanos}', '0'::jsonb)",
                "jsonb_set(catalog, '{retiredOfferings,0,offering,key}', '\"item\"'::jsonb)",
                "jsonb_set(catalog, '{retiredOfferings,1,offering,key}', catalog #> '{retiredOfferings,0,offering,key}')",
                "jsonb_set(catalog, '{retiredCategories,0,category,key}', '\"choice\"'::jsonb)",
                "jsonb_set(catalog, '{retiredOfferings,0,lastSeenRevision}', '2'::jsonb)",
                "jsonb_set(catalog, '{retiredOfferings,0,lastSeenRevision}', '0'::jsonb)",
                "jsonb_set(catalog, '{retiredCategories,0,category,maximumSelections}', '-1'::jsonb)",
            ).forEach { corruption ->
                withClue(corruption) {
                    shouldThrow<IllegalStateException> {
                        transactor.inTransaction { transaction ->
                            corrupt(transaction, first.catalogId, corruption)
                            repository.retrieveLatestVersion(transaction, first.catalogId)
                        }
                    }.message shouldContain first.catalogId.value.toString()
                }
                latest(first.catalogId) shouldBe second
            }
        }

        test("the database refuses a catalog row without an object holding all four arrays or with a revision below 1") {
            val id = catalogId().value
            val valid = """{"categories": [], "offerings": [], "retiredCategories": [], "retiredOfferings": []}"""
            listOf(
                "'[]'" to 1,
                "'{}'" to 1,
                "'null'" to 1,
                "'{\"categories\": [], \"offerings\": []}'" to 1,
                "'{\"categories\": [], \"offerings\": {}, \"retiredCategories\": [], \"retiredOfferings\": []}'" to 1,
                "'{\"categories\": [], \"offerings\": [], \"retiredCategories\": {}, \"retiredOfferings\": []}'" to 1,
                "'{\"categories\": [], \"offerings\": [], \"retiredCategories\": [], \"retiredOfferings\": null}'" to 1,
                "'$valid'" to 0,
            ).forEach { (catalog, revision) ->
                withClue("$catalog at $revision") {
                    shouldThrow<UnableToExecuteStatementException> {
                        transactor.inTransaction { transaction ->
                            transaction.handle
                                .createUpdate(
                                    "INSERT INTO commerce.offerings_catalogs (catalog_id, revision, catalog) " +
                                        "VALUES (:id, $revision, $catalog::jsonb)",
                                ).bind("id", id)
                                .execute()
                        }
                    }
                }
            }
            shouldThrow<UnableToExecuteStatementException> {
                transactor.inTransaction { transaction ->
                    transaction.handle
                        .createUpdate("INSERT INTO commerce.offerings_catalogs (catalog_id, revision) VALUES (:id, 1)")
                        .bind("id", id)
                        .execute()
                }
            }
        }

        test("each save retires what it removes and restores what returns, keeping every used key reserved") {
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
            transactor.inTransaction { repository.save(it, other) }

            fun retiredOfferings() =
                transactor.inTransaction { transaction ->
                    repository.retrieveRetiredOfferings(transaction, r1.catalogId).map {
                        Triple(it.value.key.value, it.lastSeen.revision.number, it.value.description)
                    }
                }

            fun retiredCategories() =
                transactor.inTransaction { transaction ->
                    repository.retrieveRetiredCategories(transaction, r1.catalogId).map {
                        it.value.key.value to it.lastSeen.revision.number
                    }
                }

            transactor.inTransaction { repository.save(it, r1) }
            retiredOfferings().shouldBeEmpty()
            retiredCategories().shouldBeEmpty()
            transactor.inTransaction { repository.save(it, r2) }
            retiredOfferings().shouldContainExactly(Triple("beta", 1, null), Triple("gamma", 1, "first gamma"))
            retiredCategories().shouldContainExactly("extras" to 1)
            transactor.inTransaction { repository.save(it, r3) }
            retiredOfferings().shouldContainExactly(Triple("beta", 1, null))
            retiredCategories().shouldBeEmpty()
            transactor.inTransaction { repository.save(it, r4) }
            retiredOfferings().shouldContainExactly(Triple("beta", 1, null), Triple("gamma", 3, "second gamma"))
            retiredCategories().shouldContainExactly("extras" to 3)
            latest(r1.catalogId) shouldBe r4

            transactor.inTransaction { transaction ->
                listOf("alpha", "beta", "gamma").forEach {
                    repository.offeringKeyReserved(transaction, r1.catalogId, OfferingKey(it)) shouldBe true
                }
                // Exact keys only: a prefix, another catalog's key, and an unused key are never reserved.
                listOf("alph", "alphabet", "delta", "ALPHA").forEach {
                    repository.offeringKeyReserved(transaction, r1.catalogId, OfferingKey(it)) shouldBe false
                }
                repository.offeringKeyReserved(transaction, other.catalogId, OfferingKey("delta")) shouldBe true
                repository.categoryKeyReserved(transaction, r1.catalogId, OfferingCategoryKey("extras")) shouldBe true
                repository.categoryKeyReserved(transaction, r1.catalogId, OfferingCategoryKey("elsewhere")) shouldBe false
                repository.categoryKeyReserved(transaction, other.catalogId, OfferingCategoryKey("elsewhere")) shouldBe true
                repository.retrieveRetiredOfferings(transaction, other.catalogId).shouldBeEmpty()
                // A catalog that does not exist reserves and retires nothing.
                val absent = catalogId()
                repository.offeringKeyReserved(transaction, absent, OfferingKey("alpha")) shouldBe false
                repository.categoryKeyReserved(transaction, absent, OfferingCategoryKey("choice")) shouldBe false
                repository.retrieveRetiredOfferings(transaction, absent).shouldBeEmpty()
                repository.retrieveRetiredCategories(transaction, absent).shouldBeEmpty()
            }
        }

        test("retired values are ordered by key bytes, not by database collation or UTF-16 code units") {
            val keys = listOf("b", "B", "a", "_", "😀", "～", "A")
            val full = OfferingsSnapshot.create(catalogId(), listOf(category("choice")), keys.map { offering(it, "choice") })
            val empty = full.revise(listOf(category("choice")), emptyList())
            transactor.inTransaction { repository.save(it, full) }
            transactor.inTransaction { repository.save(it, empty) }
            transactor
                .inTransaction { repository.retrieveRetiredOfferings(it, full.catalogId) }
                .map { it.value.key.value }
                .shouldContainExactly("A", "B", "_", "a", "b", "～", "😀")
        }

        test("every read and every save fails loudly when the stored catalog is not valid") {
            val r1 =
                OfferingsSnapshot.create(
                    catalogId(),
                    listOf(category("choice"), category("other")),
                    listOf(offering("item", "choice"), offering("timed", "other")),
                )
            val r2 = r1.revise(listOf(category("choice")), listOf(offering("item", "choice")))
            transactor.inTransaction {
                repository.save(it, r1)
                repository.save(it, r2)
            }
            val questions =
                mapOf<String, (Transaction) -> Any?>(
                    "latest" to { repository.retrieveLatestVersion(it, r1.catalogId) },
                    "offering key reserved" to { repository.offeringKeyReserved(it, r1.catalogId, OfferingKey("item")) },
                    "category key reserved" to { repository.categoryKeyReserved(it, r1.catalogId, OfferingCategoryKey("choice")) },
                    "retired offerings" to { repository.retrieveRetiredOfferings(it, r1.catalogId) },
                    "retired categories" to { repository.retrieveRetiredCategories(it, r1.catalogId) },
                    "save" to { repository.save(it, r2.revise(r2.categories, emptyList())) },
                )
            listOf(
                "jsonb_set(catalog, '{offerings,0,category}', '\"absent\"'::jsonb)",
                "jsonb_set(catalog, '{retiredOfferings,0,offering,key}', '\"item\"'::jsonb)",
                "jsonb_set(catalog, '{retiredCategories,0,lastSeenRevision}', '5'::jsonb)",
            ).forEach { corruption ->
                questions.forEach { (name, ask) ->
                    withClue("$name with $corruption") {
                        shouldThrow<IllegalStateException> {
                            transactor.inTransaction { transaction ->
                                corrupt(transaction, r1.catalogId, corruption)
                                ask(transaction)
                            }
                        }.message shouldContain r1.catalogId.value.toString()
                    }
                }
            }
            latest(r1.catalogId) shouldBe r2
        }

        test("concurrent successors of one revision serialize on the catalog row, and the later one conflicts") {
            val first = OfferingsSnapshot.create(catalogId(), listOf(category("choice")))
            transactor.inTransaction { repository.save(it, first) }
            val winner = first.revise(first.categories, listOf(offering("winner", "choice")))
            val loser = first.revise(first.categories, listOf(offering("loser", "choice")))

            fun backendPid(transaction: Transaction): Int =
                transaction.handle
                    .createQuery("SELECT pg_backend_pid()")
                    .mapTo(Int::class.java)
                    .one()

            // Observes PostgreSQL itself: returns the waiter's statement once it is blocked by the holder.
            fun awaitBlocked(
                waiter: Int,
                holder: Int,
            ): String {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (true) {
                    val blocked =
                        transactor.inTransaction { transaction ->
                            transaction.handle
                                .createQuery(
                                    """SELECT query FROM pg_stat_activity
                                       WHERE pid = :waiter AND wait_event_type = 'Lock' AND :holder = ANY (pg_blocking_pids(pid))""",
                                ).bind("waiter", waiter)
                                .bind("holder", holder)
                                .mapTo(String::class.java)
                                .findOne()
                                .orElse(null)
                        }
                    if (blocked != null) return blocked
                    check(System.nanoTime() < deadline) { "Backend $waiter was never blocked by backend $holder" }
                    Thread.sleep(10)
                }
            }

            val executor = Executors.newFixedThreadPool(2)
            val release = CountDownLatch(1)
            try {
                val holderPid = CompletableFuture<Int>()
                val holder =
                    executor.submit {
                        try {
                            transactor.inTransaction { transaction ->
                                repository.save(transaction, winner)
                                holderPid.complete(backendPid(transaction))
                                release.await(60, TimeUnit.SECONDS)
                            }
                        } catch (e: Throwable) {
                            holderPid.completeExceptionally(e)
                            throw e
                        }
                    }
                val holderBackend = holderPid.get(30, TimeUnit.SECONDS)
                val waiterPid = CompletableFuture<Int>()
                val waiter =
                    executor.submit(
                        Callable {
                            runCatching {
                                transactor.inTransaction { transaction ->
                                    waiterPid.complete(backendPid(transaction))
                                    repository.save(transaction, loser)
                                }
                            }
                        },
                    )
                val blockedStatement = awaitBlocked(waiterPid.get(30, TimeUnit.SECONDS), holderBackend)
                blockedStatement shouldContain "commerce.offerings_catalogs"
                blockedStatement shouldContain "FOR UPDATE"
                waiter.isDone shouldBe false

                release.countDown()
                holder.get(30, TimeUnit.SECONDS)
                (waiter.get(30, TimeUnit.SECONDS).exceptionOrNull() is CommerceFailure.Conflict) shouldBe true
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
            latest(first.catalogId) shouldBe winner
        }

        test("one caller transaction atomically commits and rolls back commerce and application rows") {
            val rolledBack = OfferingsSnapshot.create(catalogId(), listOf(category("service")), listOf(offering("standard", "service")))
            val rowId = UUID.randomUUID()
            val before = applicationRows()
            shouldThrow<IllegalStateException> {
                transactor.inTransaction { transaction ->
                    repository.save(transaction, rolledBack)
                    transaction.handle
                        .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, :value)")
                        .bind("id", rowId)
                        .bind("value", "rolled back")
                        .execute()
                    throw IllegalStateException("rollback both schemas")
                }
            }
            catalogRows(rolledBack.catalogId) shouldBe 0
            applicationRows() shouldBe before

            val committed = OfferingsSnapshot.create(catalogId(), listOf(category("service")), listOf(offering("standard", "service")))
            transactor.inTransaction { transaction ->
                repository.save(transaction, committed)
                transaction.handle
                    .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, :value)")
                    .bind("id", rowId)
                    .bind("value", "committed")
                    .execute()
            }
            catalogRows(committed.catalogId) shouldBe 1
            applicationRows() shouldBe before + 1

            // A rolled-back successor leaves the current revision and its retired entries untouched.
            shouldThrow<RollBack> {
                transactor.inTransaction { transaction ->
                    repository.save(transaction, committed.revise(committed.categories, emptyList()))
                    throw RollBack()
                }
            }
            latest(committed.catalogId) shouldBe committed
            transactor.inTransaction { repository.retrieveRetiredOfferings(it, committed.catalogId) }.shouldBeEmpty()
        }
    })
