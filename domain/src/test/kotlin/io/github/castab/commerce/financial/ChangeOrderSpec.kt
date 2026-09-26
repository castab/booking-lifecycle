package io.github.castab.commerce.financial

import io.github.castab.commerce.financial.ChangeOrder.Change
import io.github.castab.commerce.financial.fixtures.eur
import io.github.castab.commerce.financial.fixtures.lineItem
import io.github.castab.commerce.financial.fixtures.usd
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.util.UUID

class ChangeOrderSpec :
    FunSpec({

        val chairs = lineItem("Folding chairs", quantity = "3", price = usd("10.00"), taxAmount = usd("2.40"))
        val serviceFee = lineItem("Event service fee", quantity = null, price = usd("100"), taxAmount = usd("8"))
        val tables = lineItem("Tables", quantity = "2", price = usd("40.00"), taxAmount = usd("6.40"))

        fun estimate() = FinancialDocument.Estimate.create(UUID.randomUUID(), listOf(chairs, serviceFee))

        context("changes") {

            test("AddLineItem appends the line item") {
                val source = estimate()
                val successor = source.changeOrder(ChangeOrder(listOf(Change.AddLineItem(tables))))

                successor.lineItems shouldContainExactly listOf(chairs, serviceFee, tables)
            }

            test("ReplaceLineItem replaces the line item in place, keeping its id") {
                val source = estimate()
                val revisedChairs = chairs.copy(quantity = BigDecimal("50"))

                val successor = source.changeOrder(ChangeOrder(listOf(Change.ReplaceLineItem(chairs.id, revisedChairs))))

                successor.lineItems shouldContainExactly listOf(revisedChairs, serviceFee)
                successor.lineItems.first().id shouldBe chairs.id
            }

            test("RemoveLineItem removes the line item") {
                val successor = estimate().changeOrder(ChangeOrder(listOf(Change.RemoveLineItem(chairs.id))))

                successor.lineItems shouldContainExactly listOf(serviceFee)
            }

            test("multiple changes are applied in order") {
                val revisedTables = tables.copy(quantity = BigDecimal("6"), taxAmount = usd("19.20"))

                val successor =
                    estimate().changeOrder(
                        ChangeOrder(
                            listOf(
                                Change.AddLineItem(tables),
                                Change.RemoveLineItem(chairs.id),
                                // Only valid because the add above has already been applied.
                                Change.ReplaceLineItem(tables.id, revisedTables),
                            ),
                        ),
                    )

                successor.lineItems shouldContainExactly listOf(serviceFee, revisedTables)
                successor.subtotal shouldBe usd("340.00")
                successor.total shouldBe usd("367.20")
            }

            test("order matters: removing a line item before replacing it fails") {
                shouldThrow<IllegalArgumentException> {
                    estimate().changeOrder(
                        ChangeOrder(listOf(Change.RemoveLineItem(chairs.id), Change.ReplaceLineItem(chairs.id, chairs))),
                    )
                }
            }

            test("a removed id may be added again later in the same change order") {
                val successor =
                    estimate().changeOrder(
                        ChangeOrder(listOf(Change.RemoveLineItem(chairs.id), Change.AddLineItem(chairs))),
                    )

                successor.lineItems shouldContainExactly listOf(serviceFee, chairs)
            }
        }

        context("rejected changes") {

            test("adding a duplicate line item id fails") {
                shouldThrow<IllegalArgumentException> {
                    estimate().changeOrder(ChangeOrder(listOf(Change.AddLineItem(chairs.copy(description = "More chairs")))))
                }.message shouldContain "already present"
            }

            test("replacing a nonexistent id fails") {
                shouldThrow<IllegalArgumentException> {
                    estimate().changeOrder(ChangeOrder(listOf(Change.ReplaceLineItem(tables.id, tables))))
                }.message shouldContain "no such line item"
            }

            test("removing a nonexistent id fails") {
                shouldThrow<IllegalArgumentException> {
                    estimate().changeOrder(ChangeOrder(listOf(Change.RemoveLineItem(UUID.randomUUID()))))
                }.message shouldContain "no such line item"
            }

            test("a replacement with a different id is rejected") {
                shouldThrow<IllegalArgumentException> {
                    Change.ReplaceLineItem(lineItemId = chairs.id, replacement = tables)
                }.message shouldContain "must keep the id"

                val replace = Change.ReplaceLineItem(chairs.id, chairs)
                shouldThrow<IllegalArgumentException> { replace.copy(replacement = tables) }
            }

            test("removing every line item fails, because a document needs at least one") {
                shouldThrow<IllegalArgumentException> {
                    estimate().changeOrder(
                        ChangeOrder(listOf(Change.RemoveLineItem(chairs.id), Change.RemoveLineItem(serviceFee.id))),
                    )
                }.message shouldContain "at least one line item"
            }

            test("adding a line item in another currency fails") {
                shouldThrow<IllegalArgumentException> {
                    estimate().changeOrder(
                        ChangeOrder(listOf(Change.AddLineItem(lineItem("Room", quantity = null, price = eur("90"), taxAmount = eur("0"))))),
                    )
                }.message shouldContain "mixes currencies"
            }

            test("an empty change order is rejected") {
                shouldThrow<IllegalArgumentException> { ChangeOrder(emptyList()) }
            }
        }

        context("atomicity and successors") {

            test("a change order failing on its last change produces no successor and leaves the source unchanged") {
                val source = estimate()
                var successor: FinancialDocument.Estimate? = null

                shouldThrow<IllegalArgumentException> {
                    successor =
                        source.changeOrder(
                            ChangeOrder(
                                listOf(
                                    Change.AddLineItem(tables),
                                    Change.RemoveLineItem(chairs.id),
                                    Change.RemoveLineItem(UUID.randomUUID()),
                                ),
                            ),
                        )
                }

                successor shouldBe null
                source.version shouldBe Version.INITIAL
                source.lineItems shouldContainExactly listOf(chairs, serviceFee)
            }

            test("a successful change order produces exactly one next version, in the same stage") {
                val invoiceV7 =
                    FinancialDocument.Invoice.restore(
                        UUID.randomUUID(),
                        Version.of(7),
                        listOf(chairs),
                    )

                val invoiceV8 = invoiceV7.changeOrder(ChangeOrder(listOf(Change.AddLineItem(serviceFee))))

                invoiceV8.shouldBeInstanceOf<FinancialDocument.Invoice>()
                invoiceV8.id shouldBe invoiceV7.id
                invoiceV8.version shouldBe Version.of(8)
                invoiceV8.previousVersion shouldBe Version.of(7)
                invoiceV7.lineItems shouldContainExactly listOf(chairs)
            }

            test("applying the same change order to the same source twice yields equal successors") {
                // Concurrent writers computing from the same snapshot produce the same (id, version);
                // the persistence layer, not this library, decides which write wins.
                val source = estimate()
                val changeOrder = ChangeOrder(listOf(Change.AddLineItem(tables)))

                source.changeOrder(changeOrder) shouldBe source.changeOrder(changeOrder)
                source.changeOrder(changeOrder).reference shouldBe source.changeOrder(changeOrder).reference
            }
        }

        context("immutability") {

            test("mutating the caller's source list does not mutate the change order") {
                val source = mutableListOf<Change>(Change.AddLineItem(tables))
                val changeOrder = ChangeOrder(source)

                source += Change.RemoveLineItem(chairs.id)
                source.clear()

                changeOrder.changes shouldContainExactly listOf(Change.AddLineItem(tables))
            }

            test("the exposed changes cannot be mutated, even through a cast") {
                val changeOrder = ChangeOrder(listOf(Change.AddLineItem(tables)))

                @Suppress("UNCHECKED_CAST")
                val cast = changeOrder.changes as MutableList<Change>
                shouldThrow<UnsupportedOperationException> { cast.clear() }
            }

            test("change orders compare by their changes") {
                ChangeOrder(listOf(Change.RemoveLineItem(chairs.id))) shouldBe
                    ChangeOrder(mutableListOf(Change.RemoveLineItem(chairs.id)))
            }
        }
    })
