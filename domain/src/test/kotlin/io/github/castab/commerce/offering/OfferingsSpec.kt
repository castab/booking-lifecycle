package io.github.castab.commerce.offering

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency
import java.util.UUID

private val usd = Currency.getInstance("USD")

private fun money(value: String) = Money(BigDecimal(value), usd)

private fun category(
    key: String,
    min: Int = 0,
    max: Int? = null,
) = OfferingCategory(OfferingCategoryKey(key), key, minimumSelections = min, maximumSelections = max)

private fun offering(
    key: String,
    category: String,
    price: OfferingPrice? = null,
) = Offering(OfferingKey(key), OfferingCategoryKey(category), key, price = price)

private fun snapshot(
    categories: List<OfferingCategory>,
    offerings: List<Offering>,
) = OfferingsSnapshot.create(OfferingsCatalogId(UUID.randomUUID()), categories, offerings)

private fun selection(
    category: String,
    vararg offerings: String,
) = OfferingCategorySelection(OfferingCategoryKey(category), offerings.map(::OfferingKey))

private fun line(
    description: String,
    quantity: String?,
    price: String,
): LineItem = LineItem(UUID.randomUUID(), description, quantity = quantity?.toBigDecimal(), price = money(price), taxAmount = money("0.00"))

class OfferingsSpec :
    FunSpec({
        test("keys, categories, offerings, and descriptive prices enforce their own invariants") {
            shouldThrow<IllegalArgumentException> { OfferingKey(" ") }
            shouldThrow<IllegalArgumentException> { OfferingCategoryKey("two words") }
            shouldThrow<IllegalArgumentException> { QuantityDimension(" ") }
            category("optional", 0, 1).maximumSelections shouldBe 1
            category("unbounded", 0).maximumSelections shouldBe null
            category("exact", 2, 2).minimumSelections shouldBe 2
            shouldThrow<IllegalArgumentException> { category("bad", -1) }
            shouldThrow<IllegalArgumentException> { category("bad", 2, 1) }
            shouldThrow<IllegalArgumentException> { category("bad", 0, 0) }
            shouldThrow<IllegalArgumentException> { OfferingCategory(OfferingCategoryKey("bad"), "  ") }
            shouldThrow<IllegalArgumentException> { Offering(OfferingKey("bad"), OfferingCategoryKey("x"), " ") }
            offering("plain", "unbounded").price shouldBe null
            OfferingPrice.Fixed(money("120.00")).amount shouldBe money("120.00")
            OfferingPrice.PerQuantity(money("0.75"), QuantityDimension("guest")).dimension shouldBe QuantityDimension("guest")
            QuantityDimension("guest") shouldBe QuantityDimension("guest")
            (QuantityDimension("guest") == QuantityDimension("vehicle")) shouldBe false
            OfferingPrice.PerDuration(money("50.00"), Duration.ofHours(1)).interval shouldBe Duration.ofHours(1)
            shouldThrow<IllegalArgumentException> { OfferingPrice.PerDuration(money("50"), Duration.ZERO) }
            shouldThrow<IllegalArgumentException> { OfferingPrice.PerDuration(money("50"), Duration.ofSeconds(-1)) }
        }

        test("snapshots preserve identity, immediate revision lineage, order, and defensive copies") {
            val categories = mutableListOf(category("second"), category("first"))
            val offerings = mutableListOf(offering("b", "second"), offering("a", "first"))
            val first = snapshot(categories, offerings)
            categories.clear()
            offerings.clear()
            first.categories.map { it.key.value } shouldContainExactly listOf("second", "first")
            first.offerings.map { it.key.value } shouldContainExactly listOf("b", "a")
            shouldThrow<UnsupportedOperationException> { (first.categories as MutableList).clear() }
            first.revision shouldBe OfferingsRevision.INITIAL
            first.previousRevision shouldBe null
            first.reference shouldBe OfferingsSnapshotReference(first.catalogId, OfferingsRevision.INITIAL)
            first.offering(OfferingKey("a")) shouldBe offering("a", "first")
            first.offeringsIn(OfferingCategoryKey("first")) shouldContainExactly listOf(offering("a", "first"))
            val second = first.revise(first.categories, first.offerings + offering("c", "first"))
            second.catalogId shouldBe first.catalogId
            second.revision shouldBe OfferingsRevision.of(2)
            second.previousRevision shouldBe first.revision
            first.offerings.size shouldBe 2
            OfferingsSnapshot.restore(
                second.catalogId,
                second.revision,
                second.previousRevision,
                second.categories,
                second.offerings,
            ) shouldBe
                second
            shouldThrow<IllegalArgumentException> {
                OfferingsSnapshot.restore(
                    first.catalogId,
                    OfferingsRevision.of(3),
                    OfferingsRevision.INITIAL,
                    first.categories,
                    first.offerings,
                )
            }
        }

        test("snapshots reject duplicate keys and missing categories but allow an empty administrative catalog") {
            snapshot(emptyList(), emptyList()).offerings shouldBe emptyList()
            shouldThrow<IllegalArgumentException> { snapshot(listOf(category("x"), category("x")), emptyList()) }
            shouldThrow<IllegalArgumentException> { snapshot(listOf(category("x")), listOf(offering("a", "x"), offering("a", "x"))) }
            shouldThrow<IllegalArgumentException> { snapshot(listOf(category("x")), listOf(offering("a", "missing"))) }
        }

        test("dessert, taco, and detailing catalogs use the same domain vocabulary") {
            val dessert =
                snapshot(
                    listOf(category("soft-serve-flavor", 2, 2), category("cone-option", 1, 1)),
                    listOf(
                        offering("vanilla", "soft-serve-flavor"),
                        offering("chocolate", "soft-serve-flavor"),
                        offering("waffle-cone", "cone-option", OfferingPrice.PerQuantity(money("0.75"), QuantityDimension("guest"))),
                    ),
                )
            val taco =
                snapshot(
                    listOf(category("taco-filling", 2, 4), category("add-on", 0)),
                    listOf(
                        offering("carne-asada", "taco-filling"),
                        offering("chicken", "taco-filling"),
                        offering("extra-salsa", "add-on", OfferingPrice.Fixed(money("8.00"))),
                    ),
                )
            val detailing =
                snapshot(
                    listOf(category("mobile-service", 1, 1), category("add-on", 0)),
                    listOf(
                        offering("express-detail", "mobile-service", OfferingPrice.Fixed(money("120.00"))),
                        offering("wax", "add-on", OfferingPrice.PerDuration(money("20.00"), Duration.ofMinutes(30))),
                    ),
                )
            dessert.offerings.size shouldBe 3
            taco.category(OfferingCategoryKey("taco-filling"))?.maximumSelections shouldBe 4
            detailing.offering(OfferingKey("express-detail"))?.price shouldBe OfferingPrice.Fixed(money("120.00"))
        }

        test("structural validation is deterministic and prevents application policy from running") {
            val catalog =
                snapshot(
                    listOf(category("required", 1, 1), category("optional", 0, 2), category("exact", 2, 2)),
                    listOf(offering("one", "required"), offering("two", "optional"), offering("three", "optional")),
                )
            var called = false
            val engine =
                object : OfferingsEngine<Unit>() {
                    override fun evaluateValid(
                        snapshot: OfferingsSnapshot,
                        selections: OfferingSelections,
                        context: Unit,
                    ): OfferingsPolicyResult {
                        called = true
                        return OfferingsPolicyResult.Accepted(listOf(line("unused", null, "1")))
                    }
                }
            val candidate =
                OfferingSelections(
                    mutableListOf(
                        selection("unknown", "missing"),
                        selection("optional", "two", "two", "one", "missing"),
                        selection("optional"),
                        selection("required"),
                        selection("exact", "one"),
                    ),
                )
            val result = engine.evaluate(catalog, candidate, Unit) as OfferingsEvaluationResult.Rejected
            result.violations shouldContainExactly
                listOf(
                    StructuralOfferingsViolation.UnknownCategory(OfferingCategoryKey("unknown")),
                    StructuralOfferingsViolation.UnknownOffering(OfferingCategoryKey("unknown"), OfferingKey("missing")),
                    StructuralOfferingsViolation.DuplicateOffering(OfferingCategoryKey("optional"), OfferingKey("two")),
                    StructuralOfferingsViolation.OfferingInWrongCategory(OfferingCategoryKey("optional"), OfferingKey("one")),
                    StructuralOfferingsViolation.UnknownOffering(OfferingCategoryKey("optional"), OfferingKey("missing")),
                    StructuralOfferingsViolation.TooManySelections(OfferingCategoryKey("optional"), 2, 4),
                    StructuralOfferingsViolation.DuplicateCategory(OfferingCategoryKey("optional")),
                    StructuralOfferingsViolation.TooFewSelections(OfferingCategoryKey("required"), 1, 0),
                    StructuralOfferingsViolation.OfferingInWrongCategory(OfferingCategoryKey("exact"), OfferingKey("one")),
                    StructuralOfferingsViolation.TooFewSelections(OfferingCategoryKey("exact"), 2, 1),
                )
            called shouldBe false
            engine.evaluate(catalog, OfferingSelections(emptyList()), Unit).let {
                (it as OfferingsEvaluationResult.Rejected).violations shouldContainExactly
                    listOf(
                        StructuralOfferingsViolation.TooFewSelections(OfferingCategoryKey("required"), 1, 0),
                        StructuralOfferingsViolation.TooFewSelections(OfferingCategoryKey("exact"), 2, 0),
                    )
            }
        }

        test("application policy can price included selections and return custom violations") {
            val catalog =
                snapshot(
                    listOf(category("topping", 0)),
                    (1..6).map { offering("topping-$it", "topping") },
                )

            data class Context(
                val quantity: Int,
                val available: Boolean,
            )

            data class Unavailable(
                override val code: String = "SERVICE_UNAVAILABLE_FOR_CONTEXT",
            ) : OfferingsViolation
            val engine =
                object : OfferingsEngine<Context>() {
                    override fun evaluateValid(
                        snapshot: OfferingsSnapshot,
                        selections: OfferingSelections,
                        context: Context,
                    ): OfferingsPolicyResult {
                        if (!context.available) return OfferingsPolicyResult.Rejected(listOf(Unavailable()))
                        val extras =
                            (
                                selections.categories
                                    .single()
                                    .offerings.size - 4
                            ).coerceAtLeast(0)
                        return OfferingsPolicyResult.Accepted(
                            listOf(line("Additional toppings", (extras * context.quantity).toString(), "0.25")),
                        )
                    }
                }
            val choices =
                OfferingSelections(
                    listOf(selection("topping", "topping-1", "topping-2", "topping-3", "topping-4", "topping-5", "topping-6")),
                )
            val accepted = engine.evaluate(catalog, choices, Context(20, true)) as OfferingsEvaluationResult.Accepted
            accepted.evaluation.snapshot shouldBe catalog.reference
            accepted.evaluation.selections shouldBe choices
            accepted.evaluation.lineItems
                .single()
                .subtotal shouldBe money("10.00")
            FinancialDocument.Estimate.create(UUID.randomUUID(), accepted.evaluation.lineItems).lineItems shouldBe
                accepted.evaluation.lineItems
            (engine.evaluate(catalog, choices, Context(20, false)) as OfferingsEvaluationResult.Rejected).violations shouldContainExactly
                listOf(Unavailable())
            shouldThrow<IllegalArgumentException> { OfferingsPolicyResult.Rejected(emptyList()) }
            val candidateBlocks = mutableListOf(selection("topping", "topping-1"))
            val copiedChoices = OfferingSelections(candidateBlocks)
            candidateBlocks.clear()
            copiedChoices.categories.size shouldBe 1
        }

        test("valid optional, required, exact, and bounded categories reach application policy") {
            val catalog =
                snapshot(
                    listOf(category("optional", 0, 1), category("required", 1, 1), category("exact", 2, 2), category("bounded", 2, 4)),
                    listOf(
                        offering("r", "required"),
                        offering("e1", "exact"),
                        offering("e2", "exact"),
                        offering("b1", "bounded"),
                        offering("b2", "bounded"),
                        offering("b3", "bounded"),
                    ),
                )
            var called = 0
            val engine =
                object : OfferingsEngine<Unit>() {
                    override fun evaluateValid(
                        snapshot: OfferingsSnapshot,
                        selections: OfferingSelections,
                        context: Unit,
                    ): OfferingsPolicyResult {
                        called++
                        return OfferingsPolicyResult.Accepted(listOf(line("accepted", null, "1.00")))
                    }
                }
            val choices =
                OfferingSelections(
                    listOf(
                        selection("required", "r"),
                        selection("exact", "e1", "e2"),
                        selection("bounded", "b1", "b2", "b3"),
                    ),
                )
            (engine.evaluate(catalog, choices, Unit) is OfferingsEvaluationResult.Accepted) shouldBe true
            called shouldBe 1
            shouldThrow<IllegalArgumentException> {
                object : OfferingsEngine<Unit>() {
                    override fun evaluateValid(
                        snapshot: OfferingsSnapshot,
                        selections: OfferingSelections,
                        context: Unit,
                    ): OfferingsPolicyResult = OfferingsPolicyResult.Accepted(emptyList())
                }.evaluate(catalog, choices, Unit)
            }
        }

        test("separate taco and detailing engines interpret their catalogs without common policy types") {
            val taco =
                snapshot(
                    listOf(category("filling", 2, 4), category("add-on", 0, 1)),
                    listOf(
                        offering("asada", "filling"),
                        offering("chicken", "filling"),
                        offering("salsa", "add-on", OfferingPrice.Fixed(money("8.00"))),
                    ),
                )
            val tacoEngine =
                object : OfferingsEngine<Int>() {
                    override fun evaluateValid(
                        snapshot: OfferingsSnapshot,
                        selections: OfferingSelections,
                        context: Int,
                    ): OfferingsPolicyResult = OfferingsPolicyResult.Accepted(listOf(line("Tacos", context.toString(), "12.00")))
                }
            val tacoResult =
                tacoEngine.evaluate(
                    taco,
                    OfferingSelections(listOf(selection("filling", "asada", "chicken"))),
                    5,
                ) as OfferingsEvaluationResult.Accepted
            tacoResult.evaluation.lineItems
                .single()
                .subtotal shouldBe money("60.00")

            val detailing =
                snapshot(
                    listOf(category("service", 1, 1)),
                    listOf(offering("express", "service", OfferingPrice.Fixed(money("120.00")))),
                )
            val detailEngine =
                object : OfferingsEngine<Unit>() {
                    override fun evaluateValid(
                        snapshot: OfferingsSnapshot,
                        selections: OfferingSelections,
                        context: Unit,
                    ): OfferingsPolicyResult {
                        val selected =
                            snapshot.offering(
                                selections.categories
                                    .single()
                                    .offerings
                                    .single(),
                            )!!
                        val fixed = selected.price as OfferingPrice.Fixed
                        return OfferingsPolicyResult.Accepted(
                            listOf(
                                LineItem(
                                    UUID.randomUUID(),
                                    selected.displayName,
                                    quantity = null,
                                    price = fixed.amount,
                                    taxAmount = Money.zero(fixed.amount.currency),
                                ),
                            ),
                        )
                    }
                }
            val detailResult =
                detailEngine.evaluate(
                    detailing,
                    OfferingSelections(listOf(selection("service", "express"))),
                    Unit,
                ) as OfferingsEvaluationResult.Accepted
            detailResult.evaluation.lineItems
                .single()
                .total shouldBe money("120.00")
        }
    })
