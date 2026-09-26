package io.github.castab.commerce.financial

import io.github.castab.commerce.financial.ChangeOrder.Change
import io.github.castab.commerce.financial.fixtures.InMemoryFinancialDocumentHistory
import io.github.castab.commerce.financial.fixtures.lineItem
import io.github.castab.commerce.financial.fixtures.usd
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

class FinancialDocumentHistorySpec :
    FunSpec({

        val chairs = lineItem("Folding chairs", quantity = "3", price = usd("10.00"), taxAmount = usd("2.40"))
        val serviceFee = lineItem("Event service fee", quantity = null, price = usd("100"), taxAmount = usd("8"))

        /** v1 Estimate → v2 Estimate → v3 Quote → v4 Quote → v5 Invoice. */
        fun lineage(): List<FinancialDocument> {
            val v1 = FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(chairs))
            val v2 = v1.changeOrder(ChangeOrder(listOf(Change.AddLineItem(serviceFee))))
            val v3 = v2.toQuote()
            val v4 = v3.changeOrder(ChangeOrder(listOf(Change.RemoveLineItem(chairs.id))))
            val v5 = v4.toInvoice()
            return listOf(v1, v2, v3, v4, v5)
        }

        context("retrievePreviousVersion") {

            test("performs exactly one lookup, for the exact previous reference") {
                val (_, _, _, quoteV4, invoiceV5) = lineage()
                val history = mockk<FinancialDocumentHistory>()
                every { history.retrieveVersion(FinancialDocumentReference(invoiceV5.id, Version.of(4))) } returns quoteV4

                invoiceV5.retrievePreviousVersion(from = history) shouldBeSameInstanceAs quoteV4

                verify(exactly = 1) { history.retrieveVersion(FinancialDocumentReference(invoiceV5.id, Version.of(4))) }
                confirmVerified(history)
            }

            test("returns null for the first version without any lookup") {
                val history = mockk<FinancialDocumentHistory>()

                lineage().first().retrievePreviousVersion(from = history).shouldBeNull()

                verify(exactly = 0) { history.retrieveVersion(any()) }
                verify(exactly = 0) { history.retrieveLatestVersion(any()) }
                confirmVerified(history)
            }

            test("returns null when the history does not hold the previous version") {
                val invoiceV5 = lineage().last()
                val history = mockk<FinancialDocumentHistory>()
                every { history.retrieveVersion(any()) } returns null

                invoiceV5.retrievePreviousVersion(from = history).shouldBeNull()

                verify(exactly = 1) { history.retrieveVersion(invoiceV5.previousReference!!) }
            }

            test("retrieving one historical version does not retrieve the rest of the lineage") {
                val (estimateV1, estimateV2, quoteV3, quoteV4, invoiceV5) = lineage()
                val history = mockk<FinancialDocumentHistory>()
                every { history.retrieveVersion(quoteV4.reference) } returns quoteV4

                val previous = invoiceV5.retrievePreviousVersion(from = history)

                previous shouldBe quoteV4
                verify(exactly = 1) { history.retrieveVersion(quoteV4.reference) }
                listOf(estimateV1, estimateV2, quoteV3).forEach { earlier ->
                    verify(exactly = 0) { history.retrieveVersion(earlier.reference) }
                }
                confirmVerified(history)
            }

            test("rejects a history that returns a different snapshot than the one requested") {
                val (estimateV1, _, _, _, invoiceV5) = lineage()
                val history = mockk<FinancialDocumentHistory>()
                every { history.retrieveVersion(any()) } returns estimateV1

                shouldThrow<IllegalStateException> { invoiceV5.retrievePreviousVersion(from = history) }
            }
        }

        context("retrieveVersion") {

            test("looks up the requested version of the same lineage directly, skipping intervening versions") {
                val (_, estimateV2, _, _, invoiceV5) = lineage()
                val history = mockk<FinancialDocumentHistory>()
                every { history.retrieveVersion(FinancialDocumentReference(invoiceV5.id, Version.of(2))) } returns estimateV2

                invoiceV5.retrieveVersion(version = Version.of(2), from = history) shouldBeSameInstanceAs estimateV2

                verify(exactly = 1) { history.retrieveVersion(any()) }
                confirmVerified(history)
            }
        }

        context("retrieveLatestVersion") {

            test("delegates with the document's UUID") {
                val (estimateV1, _, _, _, invoiceV5) = lineage()
                val history = mockk<FinancialDocumentHistory>()
                every { history.retrieveLatestVersion(estimateV1.id) } returns invoiceV5

                estimateV1.retrieveLatestVersion(from = history) shouldBeSameInstanceAs invoiceV5

                verify(exactly = 1) { history.retrieveLatestVersion(estimateV1.id) }
                confirmVerified(history)
            }

            test("returns null when nothing is stored for the lineage") {
                val history = mockk<FinancialDocumentHistory>()
                every { history.retrieveLatestVersion(any()) } returns null

                lineage().first().retrieveLatestVersion(from = history).shouldBeNull()
            }

            test("rejects a history that returns another lineage") {
                val history = mockk<FinancialDocumentHistory>()
                every { history.retrieveLatestVersion(any()) } returns
                    FinancialDocument.Invoice.create(UUID.randomUUID(), listOf(chairs))

                shouldThrow<IllegalStateException> { lineage().first().retrieveLatestVersion(from = history) }
            }
        }

        context("an application-owned implementation") {

            test("an in-memory store can walk history one explicit step at a time") {
                val snapshots = lineage()
                val history = InMemoryFinancialDocumentHistory()
                snapshots.forEach(history::save)
                val (estimateV1, _, _, quoteV4, invoiceV5) = snapshots

                estimateV1.retrieveLatestVersion(from = history) shouldBe invoiceV5
                invoiceV5.retrievePreviousVersion(from = history) shouldBe quoteV4
                quoteV4.retrievePreviousVersion(from = history)?.retrievePreviousVersion(from = history) shouldBe snapshots[1]
            }

            test("the store, not the library, rejects a second successor for the same version") {
                val history = InMemoryFinancialDocumentHistory()
                val v1 = FinancialDocument.Quote.create(UUID.randomUUID(), listOf(chairs))
                history.save(v1)

                val first = v1.changeOrder(ChangeOrder(listOf(Change.AddLineItem(serviceFee))))
                val competing = v1.changeOrder(ChangeOrder(listOf(Change.RemoveLineItem(chairs.id), Change.AddLineItem(serviceFee))))
                history.save(first)

                competing.reference shouldBe first.reference
                shouldThrow<IllegalStateException> { history.save(competing) }
                v1.retrieveLatestVersion(from = history) shouldBe first
            }
        }
    })
