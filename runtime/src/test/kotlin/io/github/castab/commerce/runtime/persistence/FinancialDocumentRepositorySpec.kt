package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.financial.retrievePreviousVersion
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.github.castab.commerce.runtime.testing.testApplicationMigrations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

private val usd = Currency.getInstance("USD")
private val eur = Currency.getInstance("EUR")
private val jpy = Currency.getInstance("JPY")

private fun money(
    amount: String,
    currency: Currency = usd,
) = Money(BigDecimal(amount), currency)

private fun line(
    description: String = "Service",
    price: String = "10.00",
    quantity: String? = null,
    tax: String = "0.00",
    subDescription: String? = null,
    currency: Currency = usd,
    id: UUID = UUID.randomUUID(),
) = LineItem(id, description, subDescription, quantity?.let(::BigDecimal), money(price, currency), money(tax, currency))

/** The financial snapshot repository against PostgreSQL: one row per immutable snapshot, lines included. */
class FinancialDocumentRepositorySpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var dataSource: com.zaxxer.hikari.HikariDataSource
        lateinit var transactor: Transactor
        val repository = PostgresFinancialDocumentRepository()

        beforeSpec {
            database = TestDatabase.create()
            dataSource = createDataSource(database.configuration, "financial-document-repository-spec")
            MigrationLifecycle(dataSource, testApplicationMigrations()).migrate()
            transactor = Transactor(Jdbi.create(dataSource))
        }

        afterSpec {
            dataSource.close()
            database.close()
        }

        fun insert(vararg snapshots: FinancialDocument) = transactor.inTransaction { tx -> snapshots.forEach { repository.insert(tx, it) } }

        fun storedLines(reference: FinancialDocumentReference) =
            transactor.inTransaction { tx ->
                Json.parseToJsonElement(
                    tx.handle
                        .createQuery(
                            "SELECT lines::text FROM commerce.financial_document_snapshots WHERE document_id = :id AND version = :version",
                        ).bind("id", reference.id)
                        .bind("version", reference.version.number)
                        .mapTo(String::class.java)
                        .one(),
                )
            }

        test("one row stores the ordered lines in an explicit, stable representation") {
            val first = line("First", "12.3400", "2.500", "1.0300", "details", id = UUID.fromString("00000000-0000-0000-0000-000000000001"))
            val second = line("Second", "-3", null, "0", id = UUID.fromString("00000000-0000-0000-0000-000000000002"))
            val estimate = FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(first, second))
            insert(estimate)

            // jsonb does not preserve key order, so compare the parsed document. Decimals are strings, so scale is exact.
            storedLines(estimate.reference) shouldBe
                Json.parseToJsonElement(
                    """[{"id": "00000000-0000-0000-0000-000000000001", "description": "First", "subDescription": "details",
                         "quantity": "2.500", "priceAmount": "12.3400", "taxAmount": "1.0300", "currency": "USD"},
                        {"id": "00000000-0000-0000-0000-000000000002", "description": "Second", "subDescription": null,
                         "quantity": null, "priceAmount": "-3", "taxAmount": "0", "currency": "USD"}]""",
                )
        }

        test("every stage round trips ordered lines with optional fields, exact decimals, tax, and currencies") {
            val lines =
                listOf(
                    line("Unit priced", "12.3400", "2.500", "1.0300", "with details"),
                    line("Flat", "3", null, "0.5"),
                    line("Discount", "-1.10", "1", "-0.0001"),
                    line("Large", "123456789012345678901234567890.123456789", "0.000000001", "7"),
                )
            listOf<(UUID) -> FinancialDocument>(
                { FinancialDocument.Estimate.create(it, lines) },
                { FinancialDocument.Quote.create(it, lines) },
                { FinancialDocument.Invoice.create(it, lines) },
            ).forEach { create ->
                val original = create(UUID.randomUUID())
                insert(original)
                val restored = transactor.inTransaction { repository.retrieveVersion(it, original.reference) }!!
                restored shouldBe original
                restored::class shouldBe original::class
                restored.lineItems.shouldContainExactly(lines)
                // Equality of Money is scale sensitive, so this also proves trailing zeros and quantity scale survive.
                restored.lineItems.zip(lines).forEach { (stored, expected) ->
                    stored.price.amount.scale() shouldBe expected.price.amount.scale()
                    stored.taxAmount.amount.scale() shouldBe expected.taxAmount.amount.scale()
                    stored.quantity?.scale() shouldBe expected.quantity?.scale()
                }
                restored.total shouldBe original.total
            }
        }

        test("currencies other than the default round trip, including no minor units") {
            listOf(eur, jpy).forEach { currency ->
                val original = FinancialDocument.Invoice.create(UUID.randomUUID(), listOf(line(price = "1500", currency = currency)))
                insert(original)
                val restored = transactor.inTransaction { repository.retrieveVersion(it, original.reference) }!!
                restored shouldBe original
                restored.currency shouldBe currency
            }
        }

        test("versions, the latest version, and history come from each snapshot's own row") {
            val id = UUID.randomUUID()
            val first = FinancialDocument.Estimate.create(id, listOf(line("A"), line("B")))
            val second = first.changeOrder(ChangeOrder(listOf(ChangeOrder.Change.AddLineItem(line("C")))))
            val quote = second.toQuote()
            val third = quote.changeOrder(ChangeOrder(listOf(ChangeOrder.Change.AddLineItem(line("D")))))
            val invoice = third.toInvoice()
            insert(first, second, quote, third, invoice)

            transactor.inTransaction { tx ->
                repository.history(tx, id).shouldContainExactly(first, second, quote, third, invoice)
                repository.history(tx, id).map { it.lineItems.size } shouldContainExactly listOf(2, 3, 3, 4, 4)
                repository.retrieveLatestVersion(tx, id) shouldBe invoice
                listOf(first, second, quote, third, invoice).forEach {
                    repository.retrieveVersion(tx, it.reference) shouldBe it
                }
                repository.retrieveVersion(tx, FinancialDocumentReference(id, Version.of(6))).shouldBeNull()
                repository.retrieveLatestVersion(tx, UUID.randomUUID()).shouldBeNull()
                repository.history(tx, UUID.randomUUID()).shouldBeEmpty()
                invoice.retrievePreviousVersion(repository.asHistory(tx)) shouldBe third
            }
        }

        test("a successor needs its stored predecessor and a legal stage, and a snapshot is stored once") {
            val estimate = FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(line()))
            // The predecessor was never stored.
            shouldThrow<CommerceFailure.Conflict> { insert(estimate.toQuote()) }
            insert(estimate)
            shouldThrow<CommerceFailure.Conflict> { insert(estimate) }
            // An invoice cannot be followed by an earlier stage, even when the snapshot itself is a valid restoration.
            val invoice = FinancialDocument.Invoice.create(UUID.randomUUID(), listOf(line()))
            insert(invoice)
            val backwards = FinancialDocument.Quote.restore(invoice.id, Version.of(2), invoice.lineItems)
            shouldThrow<CommerceFailure.Conflict> { insert(backwards) }
            transactor.inTransaction { repository.history(it, invoice.id) }.shouldContainExactly(invoice)
            transactor.inTransaction { repository.history(it, estimate.id) }.shouldContainExactly(estimate)
        }

        test("a rolled back insert leaves no snapshot and no lines") {
            val estimate = FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(line("A"), line("B")))
            shouldThrow<IllegalStateException> {
                transactor.inTransaction { tx ->
                    repository.insert(tx, estimate)
                    throw IllegalStateException("roll back")
                }
            }
            transactor.inTransaction { repository.history(it, estimate.id) }.shouldBeEmpty()
        }

        test("payment allocations keep referencing the exact stored document version") {
            val estimate = FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(line()))
            insert(estimate)
            val paymentId = UUID.randomUUID()

            fun allocate(version: Int) =
                transactor.inTransaction { tx ->
                    tx.handle.execute(
                        "INSERT INTO commerce.payment_records " +
                            "(payment_id, amount, currency, method, received_at_seconds, received_at_nanos) " +
                            "VALUES (?, 5, 'USD', 'CASH', 0, 0) ON CONFLICT DO NOTHING",
                        paymentId,
                    )
                    tx.handle.execute(
                        "INSERT INTO commerce.payment_allocations " +
                            "(allocation_id, payment_id, document_id, document_version, amount, currency, " +
                            "allocated_at_seconds, allocated_at_nanos) " +
                            "VALUES (?, ?, ?, ?, 5, 'USD', 0, 0)",
                        UUID.randomUUID(),
                        paymentId,
                        estimate.id,
                        version,
                    )
                }
            allocate(1)
            shouldThrow<UnableToExecuteStatementException> { allocate(2) }
            insert(estimate.toQuote())
            allocate(2)
        }

        test("malformed stored lines fail loudly on every read and are never repaired") {
            val estimate = FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(line("A"), line("B", id = UUID.randomUUID())))
            insert(estimate)
            listOf(
                // wrong shape
                "lines || '[1]'::jsonb",
                "jsonb_set(lines, '{0,unexpected}', '1'::jsonb)",
                "lines #- '{0,description}'",
                "lines #- '{0,subDescription}'",
                "lines #- '{0,quantity}'",
                "jsonb_set(lines, '{0,description}', 'null'::jsonb)",
                // values that are not exact decimals, UUIDs, or currencies
                "jsonb_set(lines, '{0,priceAmount}', '10.00'::jsonb)",
                "jsonb_set(lines, '{0,priceAmount}', '\"1e1\"'::jsonb)",
                "jsonb_set(lines, '{0,priceAmount}', '\"\"'::jsonb)",
                "jsonb_set(lines, '{0,taxAmount}', '\"NaN\"'::jsonb)",
                "jsonb_set(lines, '{0,quantity}', '\" 2\"'::jsonb)",
                "jsonb_set(lines, '{0,currency}', '\"usd\"'::jsonb)",
                "jsonb_set(lines, '{0,currency}', '\"XXY\"'::jsonb)",
                "jsonb_set(lines, '{0,id}', '\"1-1-1-1-1\"'::jsonb)",
                "jsonb_set(lines, '{0,id}', '\"not-a-uuid\"'::jsonb)",
                // each value is well formed but a domain invariant is broken
                "'[]'::jsonb",
                "jsonb_set(lines, '{0,description}', '\" \"'::jsonb)",
                "jsonb_set(lines, '{1,id}', lines #> '{0,id}')",
                "jsonb_set(lines, '{1,currency}', '\"EUR\"'::jsonb)",
            ).forEach { corruption ->
                val failure =
                    shouldThrow<IllegalStateException> {
                        transactor.inTransaction { tx ->
                            tx.handle
                                .createUpdate(
                                    "UPDATE commerce.financial_document_snapshots SET lines = $corruption WHERE document_id = :id",
                                ).bind("id", estimate.id)
                                .execute()
                            repository.retrieveVersion(tx, estimate.reference)
                        }
                    }
                failure.message shouldContain estimate.id.toString()
                listOf<(Transaction) -> Any?>(
                    { repository.retrieveLatestVersion(it, estimate.id) },
                    { repository.history(it, estimate.id) },
                ).forEach { read ->
                    shouldThrow<IllegalStateException> {
                        transactor.inTransaction { tx ->
                            tx.handle
                                .createUpdate(
                                    "UPDATE commerce.financial_document_snapshots SET lines = $corruption WHERE document_id = :id",
                                ).bind("id", estimate.id)
                                .execute()
                            read(tx)
                        }
                    }
                }
                // PostgreSQL rolled the corruption back with the failed transaction.
                transactor.inTransaction { repository.retrieveVersion(it, estimate.reference) } shouldBe estimate
            }
        }

        test("a stored stage outside the supported set fails the stage check, and an unknown one never defaults") {
            val estimate = FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(line()))
            insert(estimate)
            shouldThrow<UnableToExecuteStatementException> {
                transactor.inTransaction { tx ->
                    tx.handle
                        .createUpdate("UPDATE commerce.financial_document_snapshots SET stage = 'RECEIPT' WHERE document_id = :id")
                        .bind("id", estimate.id)
                        .execute()
                }
            }
            transactor.inTransaction { repository.retrieveVersion(it, estimate.reference) } shouldBe estimate
        }
    })
