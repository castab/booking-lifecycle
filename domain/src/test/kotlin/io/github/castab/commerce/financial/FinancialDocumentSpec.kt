package io.github.castab.commerce.financial

import io.github.castab.commerce.financial.ChangeOrder.Change
import io.github.castab.commerce.financial.fixtures.USD
import io.github.castab.commerce.financial.fixtures.eur
import io.github.castab.commerce.financial.fixtures.lineItem
import io.github.castab.commerce.financial.fixtures.usd
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.util.UUID

/*
 * Intentionally invalid API usage. None of these compile, because the transitions are
 * absent from the stage types:
 *
 *     estimate.toInvoice()   // an established estimate must be quoted first
 *     quote.toEstimate()     // no reverse transitions
 *     invoice.toQuote()
 *     invoice.toEstimate()
 *     FinancialDocument.Invoice(id, Version.of(4), Version.of(3), items)   // constructors are private
 *     document.version = Version.of(9)                                    // snapshots are immutable
 */

/** Compiles without an `else` branch only because [FinancialDocument] is sealed. */
private fun stageOf(document: FinancialDocument): String =
    when (document) {
        is FinancialDocument.Estimate -> "estimate"
        is FinancialDocument.Quote -> "quote"
        is FinancialDocument.Invoice -> "invoice"
    }

/**
 * The public instance methods a class declares itself, excluding compiler-generated bridges
 * and the static `create`/`restore` factories that `@JvmStatic` copies onto the class.
 */
private fun Class<*>.publicInstanceMethodNames(): List<String> =
    declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
        .filterNot { it.isBridge || it.isSynthetic }
        .map(Method::getName)

class FinancialDocumentSpec :
    FunSpec({

        val chairs = lineItem("Folding chairs", quantity = "3", price = usd("10.00"), taxAmount = usd("2.40"))
        val serviceFee = lineItem("Event service fee", quantity = null, price = usd("100"), taxAmount = usd("8"))
        val items = listOf(chairs, serviceFee)

        context("direct creation") {

            test("an Estimate can start a lineage at version 1 with no previous version") {
                val id = UUID.randomUUID()
                val estimate = FinancialDocument.Estimate.create(id = id, lineItems = items)

                estimate.id shouldBe id
                estimate.version shouldBe Version.INITIAL
                estimate.previousVersion.shouldBeNull()
                estimate.lineItems shouldContainExactly items
            }

            test("a Quote can start a lineage without an Estimate") {
                val id = UUID.randomUUID()
                val quote = FinancialDocument.Quote.create(id = id, lineItems = items)

                quote.id shouldBe id
                quote.version shouldBe Version.INITIAL
                quote.previousVersion.shouldBeNull()
                quote.lineItems shouldContainExactly items
            }

            test("a lineage keeps only its identity and financial contents across revisions and stages") {
                val estimate = FinancialDocument.Estimate.create(UUID.randomUUID(), items)
                val revised = estimate.changeOrder(ChangeOrder(listOf(Change.ReplaceLineItem(chairs.id, chairs))))
                val quote = revised.toQuote()
                val invoice = quote.toInvoice()

                listOf(estimate, revised, quote, invoice).map { it.id }.toSet() shouldBe setOf(estimate.id)
                listOf(estimate, revised, quote, invoice).map { it.lineItems }.toSet() shouldBe setOf(items)
            }

            test("an Invoice can start a lineage directly, as at a point of sale") {
                val id = UUID.randomUUID()
                val purchased =
                    listOf(
                        lineItem("Coffee beans (bag)", quantity = "2", price = usd("14.00"), taxAmount = usd("2.24")),
                    )

                val invoice = FinancialDocument.Invoice.create(id = id, lineItems = purchased)

                invoice.id shouldBe id
                invoice.version shouldBe Version.INITIAL
                invoice.previousVersion.shouldBeNull()
                invoice.previousReference.shouldBeNull()
                invoice.lineItems shouldContainExactly purchased
                invoice.total shouldBe usd("30.24")

                // A directly created invoice is a complete invoice: it can be revised like any other.
                val revised =
                    invoice.changeOrder(
                        ChangeOrder(listOf(Change.RemoveLineItem(purchased.single().id), Change.AddLineItem(serviceFee))),
                    )
                revised.shouldBeInstanceOf<FinancialDocument.Invoice>()
                revised.version shouldBe Version.of(2)
            }

            test("a document must contain at least one line item") {
                shouldThrow<IllegalArgumentException> {
                    FinancialDocument.Estimate.create(UUID.randomUUID(), emptyList())
                }.message shouldContain "at least one line item"
            }

            test("line item ids must be unique within a document") {
                shouldThrow<IllegalArgumentException> {
                    FinancialDocument.Quote.create(
                        UUID.randomUUID(),
                        listOf(chairs, chairs.copy(description = "Chairs")),
                    )
                }.message shouldContain "duplicate line item ids"
            }
        }

        context("versioning and lineage") {

            val estimateV1 = FinancialDocument.Estimate.create(UUID.randomUUID(), items)
            val addTables =
                ChangeOrder(
                    listOf(Change.AddLineItem(lineItem("Tables", quantity = "2", price = usd("40"), taxAmount = usd("6.40")))),
                )

            test("changeOrder produces the next version of the same lineage") {
                val estimateV2 = estimateV1.changeOrder(addTables)

                estimateV2.id shouldBe estimateV1.id
                estimateV2.version shouldBe estimateV1.version.next()
                estimateV2.previousVersion shouldBe estimateV1.version
            }

            test("toQuote produces the next version, carrying the line items forward unchanged") {
                val estimateV2 = estimateV1.changeOrder(addTables)
                val quoteV3 = estimateV2.toQuote()

                quoteV3.id shouldBe estimateV2.id
                quoteV3.version shouldBe Version.of(3)
                quoteV3.previousVersion shouldBe estimateV2.version
                quoteV3.lineItems shouldContainExactly estimateV2.lineItems
                quoteV3.total shouldBe estimateV2.total
            }

            test("toInvoice produces the next version, carrying the line items forward unchanged") {
                val quoteV4 =
                    FinancialDocument.Quote
                        .create(UUID.randomUUID(), items)
                        .changeOrder(addTables)
                        .changeOrder(ChangeOrder(listOf(Change.RemoveLineItem(serviceFee.id))))
                        .changeOrder(ChangeOrder(listOf(Change.AddLineItem(serviceFee))))
                val invoiceV5 = quoteV4.toInvoice()

                quoteV4.version shouldBe Version.of(4)
                invoiceV5.id shouldBe quoteV4.id
                invoiceV5.version shouldBe Version.of(5)
                invoiceV5.previousVersion shouldBe quoteV4.version
                invoiceV5.lineItems shouldContainExactly quoteV4.lineItems
            }

            test("the full lifecycle keeps one UUID and links each snapshot only to its predecessor") {
                val someItem = chairs
                val additionalItem = lineItem("Tables", quantity = "2", price = usd("40"), taxAmount = usd("6.40"))
                val finalAdjustment = lineItem("Overtime", quantity = null, price = usd("75"), taxAmount = usd("0"))

                val estimateV1 = FinancialDocument.Estimate.create(id = UUID.randomUUID(), lineItems = items)
                val estimateV2 =
                    estimateV1.changeOrder(
                        ChangeOrder(
                            changes =
                                listOf(
                                    Change.ReplaceLineItem(
                                        lineItemId = someItem.id,
                                        replacement = someItem.copy(quantity = BigDecimal("50")),
                                    ),
                                ),
                        ),
                    )
                val quoteV3 = estimateV2.toQuote()
                val quoteV4 = quoteV3.changeOrder(ChangeOrder(changes = listOf(Change.AddLineItem(additionalItem))))
                val invoiceV5 = quoteV4.toInvoice()
                val invoiceV6 = invoiceV5.changeOrder(ChangeOrder(changes = listOf(Change.AddLineItem(finalAdjustment))))

                val lineage: List<FinancialDocument> = listOf(estimateV1, estimateV2, quoteV3, quoteV4, invoiceV5, invoiceV6)

                lineage.map(::stageOf) shouldContainExactly
                    listOf("estimate", "estimate", "quote", "quote", "invoice", "invoice")
                lineage.map { it.id }.toSet() shouldBe setOf(estimateV1.id)
                lineage.map { it.version.number } shouldContainExactly listOf(1, 2, 3, 4, 5, 6)
                lineage.map { it.previousVersion?.number } shouldContainExactly listOf(null, 1, 2, 3, 4, 5)
                lineage.zipWithNext().forEach { (previous, next) ->
                    next.previousReference shouldBe previous.reference
                }

                invoiceV6.lineItems.map { it.description } shouldContainExactly
                    listOf("Folding chairs", "Event service fee", "Tables", "Overtime")
                invoiceV6.subtotal shouldBe usd("755.00") // 50 × 10.00 + 100 + 2 × 40 + 75
            }

            test("reference and previousReference identify snapshots without embedding them") {
                val estimateV2 = estimateV1.changeOrder(addTables)

                estimateV1.reference shouldBe FinancialDocumentReference(estimateV1.id, Version.INITIAL)
                estimateV1.previousReference.shouldBeNull()
                estimateV2.reference shouldBe FinancialDocumentReference(estimateV1.id, Version.of(2))
                estimateV2.previousReference shouldBe estimateV1.reference
            }

            test("snapshots hold no reference to other snapshots") {
                // Only the id, versions, and line items are stored: no field can hold a
                // predecessor, so loading a snapshot can never load a chain of history.
                val fieldTypes =
                    generateSequence<Class<*>>(FinancialDocument.Invoice::class.java) { it.superclass }
                        .flatMap { it.declaredFields.asSequence() }
                        .map { it.type }
                        .toList()

                fieldTypes.none { FinancialDocument::class.java.isAssignableFrom(it) } shouldBe true
                fieldTypes.none { FinancialDocumentReference::class.java.isAssignableFrom(it) } shouldBe true
            }
        }

        context("lifecycle typing") {

            val estimate = FinancialDocument.Estimate.create(UUID.randomUUID(), items)
            val changeOrder = ChangeOrder(listOf(Change.RemoveLineItem(chairs.id)))

            test("each stage's changeOrder returns the same stage, and transitions move one stage forward") {
                // Each explicit type below compiles only because of the declared return types.
                val revisedEstimate: FinancialDocument.Estimate = estimate.changeOrder(changeOrder)
                val quote: FinancialDocument.Quote = estimate.toQuote()
                val revisedQuote: FinancialDocument.Quote = quote.changeOrder(changeOrder)
                val invoice: FinancialDocument.Invoice = quote.toInvoice()
                val revisedInvoice: FinancialDocument.Invoice = invoice.changeOrder(changeOrder)

                stageOf(revisedEstimate) shouldBe "estimate"
                stageOf(quote) shouldBe "quote"
                stageOf(revisedQuote) shouldBe "quote"
                stageOf(invoice) shouldBe "invoice"
                stageOf(revisedInvoice) shouldBe "invoice"
            }

            test("through the sealed type, changeOrder still preserves the runtime stage") {
                val documents: List<FinancialDocument> = listOf(estimate, estimate.toQuote(), estimate.toQuote().toInvoice())

                documents.map { stageOf(it.changeOrder(changeOrder)) } shouldContainExactly
                    listOf("estimate", "quote", "invoice")
            }

            test("each stage declares exactly its legal lifecycle operations") {
                FinancialDocument.Estimate::class.java
                    .publicInstanceMethodNames()
                    .shouldContainExactlyInAnyOrder("changeOrder", "toQuote")
                FinancialDocument.Quote::class.java
                    .publicInstanceMethodNames()
                    .shouldContainExactlyInAnyOrder("changeOrder", "toInvoice")
                FinancialDocument.Invoice::class.java
                    .publicInstanceMethodNames()
                    .shouldContainExactlyInAnyOrder("changeOrder")
            }

            test("no stage exposes a public constructor, so versions and transitions cannot be forged") {
                listOf(
                    FinancialDocument::class.java,
                    FinancialDocument.Estimate::class.java,
                    FinancialDocument.Quote::class.java,
                    FinancialDocument.Invoice::class.java,
                ).forEach { type ->
                    // Kotlin's synthetic accessors are invisible to Java and Kotlin source alike.
                    type.constructors.filterNot { it.isSynthetic }.shouldBeEmpty()
                }
            }

            test("creation accepts no caller-supplied version or totals") {
                listOf(
                    FinancialDocument.Estimate::class.java,
                    FinancialDocument.Quote::class.java,
                    FinancialDocument.Invoice::class.java,
                ).forEach { stage ->
                    val create = stage.getMethod("create", UUID::class.java, List::class.java)
                    Modifier.isStatic(create.modifiers) shouldBe true
                    create.parameterTypes.toList() shouldContainExactly listOf(UUID::class.java, List::class.java)
                    val restore = stage.getMethod("restore", UUID::class.java, Version::class.java, List::class.java)
                    restore.parameterTypes.toList() shouldContainExactly
                        listOf(UUID::class.java, Version::class.java, List::class.java)
                }
            }

            test("a document is an independent financial fact with no application relationship") {
                // Customers, inquiries, bookings, and other application entities relate to a
                // document from outside it. Its public state is its identity, lineage, and
                // financial contents only, with no owner, reference, or metadata field.
                val publicState =
                    FinancialDocument::class.java.declaredMethods
                        .filter { Modifier.isPublic(it.modifiers) && it.parameterCount == 0 && it.name.startsWith("get") }
                        .map { it.name }
                publicState shouldContainExactlyInAnyOrder
                    listOf(
                        "getId",
                        "getVersion",
                        "getPreviousVersion",
                        "getLineItems",
                        "getCurrency",
                        "getSubtotal",
                        "getTaxAmount",
                        "getTotal",
                        "getReference",
                        "getPreviousReference",
                    )
                listOf(
                    FinancialDocument.Estimate::class.java,
                    FinancialDocument.Quote::class.java,
                    FinancialDocument.Invoice::class.java,
                ).forEach { stage ->
                    stage.declaredMethods
                        .filter { Modifier.isPublic(it.modifiers) && it.name.startsWith("get") }
                        .shouldBeEmpty()
                }
            }
        }

        context("immutability") {

            test("a change order leaves the source snapshot unchanged") {
                val estimate = FinancialDocument.Estimate.create(UUID.randomUUID(), items)
                val totalBefore = estimate.total

                estimate.changeOrder(ChangeOrder(listOf(Change.RemoveLineItem(chairs.id))))

                estimate.version shouldBe Version.INITIAL
                estimate.previousVersion.shouldBeNull()
                estimate.lineItems shouldContainExactly items
                estimate.total shouldBe totalBefore
            }

            test("toQuote leaves the source Estimate unchanged") {
                val estimate = FinancialDocument.Estimate.create(UUID.randomUUID(), items)

                estimate.toQuote()

                estimate.shouldBeInstanceOf<FinancialDocument.Estimate>()
                estimate.version shouldBe Version.INITIAL
                estimate.lineItems shouldContainExactly items
            }

            test("toInvoice leaves the source Quote unchanged") {
                val quote = FinancialDocument.Quote.create(UUID.randomUUID(), items)

                quote.toInvoice()

                quote.shouldBeInstanceOf<FinancialDocument.Quote>()
                quote.version shouldBe Version.INITIAL
                quote.lineItems shouldContainExactly items
            }

            test("mutating the caller's source list does not mutate the snapshot") {
                val source = mutableListOf(chairs)
                val invoice = FinancialDocument.Invoice.create(id = UUID.randomUUID(), lineItems = source)

                source += serviceFee
                source.removeAt(0)

                invoice.lineItems shouldContainExactly listOf(chairs)
                invoice.total shouldBe usd("32.40")
            }

            test("the exposed line items cannot be mutated, even through a cast") {
                val invoice = FinancialDocument.Invoice.create(UUID.randomUUID(), items)

                @Suppress("UNCHECKED_CAST")
                val cast = invoice.lineItems as MutableList<LineItem>
                shouldThrow<UnsupportedOperationException> { cast += serviceFee }
                invoice.lineItems shouldContainExactly items
            }
        }

        context("document calculations") {

            test("subtotal, tax amount, and total are derived from every line item") {
                val tables = lineItem("Tables", quantity = "2", price = usd("40.00"), taxAmount = usd("6.40"))
                val quote =
                    FinancialDocument.Quote.create(
                        UUID.randomUUID(),
                        listOf(chairs, serviceFee, tables),
                    )

                quote.subtotal shouldBe usd("210.00") // 30.00 + 100 + 80.00
                quote.taxAmount shouldBe usd("16.80") // 2.40 + 8 + 6.40
                quote.total shouldBe usd("226.80")
                quote.total shouldBe quote.lineItems.map { it.total }.reduce(Money::plus)
                quote.currency shouldBe USD
            }

            test("totals are recalculated for every successor") {
                val quote = FinancialDocument.Quote.create(UUID.randomUUID(), items)
                val revised =
                    quote.changeOrder(
                        ChangeOrder(
                            listOf(Change.ReplaceLineItem(chairs.id, chairs.copy(quantity = BigDecimal("5"), taxAmount = usd("4.00")))),
                        ),
                    )

                quote.total shouldBe usd("140.40")
                revised.subtotal shouldBe usd("150.00")
                revised.taxAmount shouldBe usd("12.00")
                revised.total shouldBe usd("162.00")
            }
        }

        context("currency") {

            test("a document in a single currency calculates normally") {
                val invoice =
                    FinancialDocument.Invoice.create(
                        UUID.randomUUID(),
                        listOf(
                            lineItem("Room", quantity = "2", price = eur("90"), taxAmount = eur("12.60")),
                            lineItem("City tax", quantity = null, price = eur("5"), taxAmount = eur("0")),
                        ),
                    )

                invoice.total shouldBe eur("197.60")
            }

            test("a document mixing currencies is rejected") {
                shouldThrow<IllegalArgumentException> {
                    FinancialDocument.Estimate.create(
                        UUID.randomUUID(),
                        listOf(chairs, lineItem("Room", quantity = null, price = eur("90"), taxAmount = eur("0"))),
                    )
                }.message shouldContain "mixes currencies"
            }
        }

        context("restoring stored snapshots") {

            test("restore rebuilds an equal snapshot with the derived previous version") {
                val quoteV3 =
                    FinancialDocument.Estimate
                        .create(UUID.randomUUID(), items)
                        .changeOrder(ChangeOrder(listOf(Change.RemoveLineItem(serviceFee.id))))
                        .toQuote()

                val restored = FinancialDocument.Quote.restore(quoteV3.id, Version.of(3), quoteV3.lineItems)

                restored shouldBe quoteV3
                restored.hashCode() shouldBe quoteV3.hashCode()
                restored.previousVersion shouldBe Version.of(2)
                restored.total shouldBe quoteV3.total
            }

            test("restoring version 1 has no previous version") {
                FinancialDocument.Invoice
                    .restore(
                        UUID.randomUUID(),
                        Version.INITIAL,
                        items,
                    ).previousVersion
                    .shouldBeNull()
                FinancialDocument.Estimate
                    .restore(
                        UUID.randomUUID(),
                        Version.of(37),
                        items,
                    ).previousVersion shouldBe
                    Version.of(36)
            }

            test("restored snapshots are validated like any other") {
                shouldThrow<IllegalArgumentException> {
                    FinancialDocument.Invoice.restore(UUID.randomUUID(), Version.of(4), emptyList())
                }
            }
        }

        context("equality") {

            test("snapshots are equal only with the same stage, id, version, and line items") {
                val id = UUID.randomUUID()
                val estimate = FinancialDocument.Estimate.create(id, items)

                estimate shouldBe FinancialDocument.Estimate.create(id, items)
                estimate shouldNotBe FinancialDocument.Quote.create(id, items)
                estimate shouldNotBe FinancialDocument.Estimate.create(UUID.randomUUID(), items)
                estimate shouldNotBe FinancialDocument.Estimate.restore(id, Version.of(2), items)
                estimate shouldNotBe FinancialDocument.Estimate.create(id, listOf(chairs))
            }

            test("toString names the stage and version") {
                val invoice = FinancialDocument.Invoice.create(UUID.randomUUID(), items)

                invoice.toString() shouldStartWith "Invoice(id=${invoice.id}, version=v1, previousVersion=null"
            }
        }
    })
