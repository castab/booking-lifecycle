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
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.testApplicationMigrations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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

        test("revisions round trip with order, nullable fields, exact prices, and isolated catalog histories") {
            val first =
                OfferingsSnapshot.create(
                    catalogId(),
                    listOf(category("second", "shown first"), category("first")),
                    listOf(
                        offering("none", "second"),
                        offering("fixed", "first", "a fixed price", OfferingPrice.Fixed(money("120.00"))),
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
