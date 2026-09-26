package io.github.castab.commerce.financial

import io.github.castab.commerce.financial.ChangeOrder.Change
import java.util.Collections
import java.util.Currency
import java.util.UUID

/**
 * One immutable snapshot of a commercial document: an [Estimate], a [Quote], or an
 * [Invoice].
 *
 * ## Lineage and versions
 *
 * Every snapshot belongs to a lineage identified by [id]. A lineage starts at
 * [Version.INITIAL] with no [previousVersion]. Nothing ever modifies a snapshot. Every
 * commercial modification ([changeOrder]) and every lifecycle transition
 * ([Estimate.toQuote], [Quote.toInvoice]) returns a *new* snapshot with the same [id], the
 * next [version], and a [previousVersion] equal to the source's version:
 *
 * ```text
 * ABC v1 Estimate ─changeOrder→ ABC v2 Estimate ─toQuote→ ABC v3 Quote
 *                 ─changeOrder→ ABC v4 Quote    ─toInvoice→ ABC v5 Invoice
 * ```
 *
 * The stage may change along a lineage. The [id] never does.
 *
 * ## Lifecycle stage
 *
 * The stage is the sealed subtype itself. There is no mutable stage or type property, and
 * `when` over a [FinancialDocument] is exhaustive without an `else` branch. The legal
 * transitions are exactly the functions each stage declares:
 *
 * ```text
 * Estimate ─toQuote()──→ Quote ─toInvoice()──→ Invoice
 * ```
 *
 * A lineage may *begin* at any stage: [Estimate.create], [Quote.create], and
 * [Invoice.create] are all first-class entry points. Once a lineage exists it moves only
 * forward, one stage at a time. There is no `Estimate → Invoice` shortcut and no reverse
 * transition.
 *
 * ## Derived totals
 *
 * A document holds at least one [LineItem], and all of its line items share one
 * [currency]. [subtotal], [taxAmount], and [total] are always calculated from the line
 * items and can never be supplied by a caller, so a document cannot contradict its own
 * lines.
 *
 * ## Relationships are application-owned
 *
 * A financial document is an independent financial fact. It does not know who requested
 * it, which customer it concerns, which inquiry produced it, or which booking it may be
 * associated with. Those are application entities and relationships: an application
 * persists them itself, outside the document, keyed by the document's [id] or
 * [reference]. Payment and refund facts likewise remain outside the document.
 *
 * ## History and persistence
 *
 * A snapshot refers to its predecessor only through [previousVersion] (or
 * [previousReference]); it never embeds earlier snapshots. Loading one snapshot therefore
 * never loads its history. A persisted lineage is naturally one row or document per
 * snapshot, keyed by `(id, version)`. Earlier snapshots are retrieved on request through an
 * application-supplied [FinancialDocumentHistory].
 *
 * This library does not coordinate concurrent writers. Two processes holding the same
 * snapshot can each compute a legitimate successor with the same version. The persistence
 * layer must reject the second write, typically with a uniqueness constraint or an
 * optimistic-concurrency check on `(id, version)`.
 *
 * ## Settlement is outside the document
 *
 * A financial document describes what is being charged and how that description evolved.
 * It does not describe settlement and holds no settlement state: no amount paid, balance,
 * payment status, payments, or refunds. Settlement is a separate bounded context that
 * references documents, never the reverse. This library models it in
 * `io.github.castab.commerce.payment`, where payment allocations reference a document
 * snapshot by its [reference] and balances are derived, never stored. Applications still
 * own persistence, processor integration, and payment policy.
 */
sealed class FinancialDocument(
    /** The identity of the lineage this snapshot belongs to. Shared by every version. */
    val id: UUID,
    /** The position of this snapshot within its lineage. */
    val version: Version,
    /**
     * The version this snapshot was derived from, or `null` for the first snapshot of a
     * lineage. Always the version immediately preceding [version].
     */
    val previousVersion: Version?,
    lineItems: List<LineItem>,
) {
    /** The line items of this snapshot, in order. Never empty and never modified. */
    val lineItems: List<LineItem> = lineItems.toImmutableList()

    /** The single currency shared by every line item and every total of this document. */
    val currency: Currency

    /** The sum of every line item's [LineItem.subtotal]. Excludes tax. */
    val subtotal: Money

    /** The sum of every line item's [LineItem.taxAmount]. */
    val taxAmount: Money

    /** The document total: [subtotal] plus [taxAmount]. */
    val total: Money

    init {
        require(if (previousVersion == null) version == Version.INITIAL else previousVersion.next() == version) {
            "Financial document $id at $version cannot have previous version $previousVersion"
        }
        require(this.lineItems.isNotEmpty()) {
            "Financial document $id at $version must contain at least one line item"
        }
        currency = this.lineItems.first().currency
        this.lineItems.forEach { lineItem ->
            require(lineItem.currency == currency) {
                "Financial document $id mixes currencies: line item ${lineItem.id} is in " +
                    "${lineItem.currency.currencyCode}, but the document is in ${currency.currencyCode}"
            }
        }
        val duplicateIds =
            this.lineItems
                .groupingBy { it.id }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        require(duplicateIds.isEmpty()) {
            "Financial document $id contains duplicate line item ids: $duplicateIds"
        }
        subtotal = this.lineItems.map { it.subtotal }.reduce(Money::plus)
        taxAmount = this.lineItems.map { it.taxAmount }.reduce(Money::plus)
        total = subtotal + taxAmount
    }

    /** The reference that identifies exactly this snapshot. */
    val reference: FinancialDocumentReference
        get() = FinancialDocumentReference(id, version)

    /**
     * The reference of the snapshot this one was derived from, or `null` for the first
     * snapshot of a lineage. Only the reference: the earlier snapshot itself is never
     * loaded or held. See [retrievePreviousVersion].
     */
    val previousReference: FinancialDocumentReference?
        get() = previousVersion?.let { FinancialDocumentReference(id, it) }

    /**
     * Applies [changeOrder] and returns the successor snapshot: same [id], same stage, the
     * next [version], and [previousVersion] equal to this snapshot's [version].
     *
     * The changes are applied in order and atomically. If any change fails (a duplicate
     * added id, a missing replaced or removed id), or the result would be an invalid
     * document (no line items, mixed currencies), an [IllegalArgumentException] is thrown
     * and no successor exists. This snapshot is never modified.
     *
     * A change order never changes the lifecycle stage. Each stage narrows the return type
     * to itself.
     */
    abstract fun changeOrder(changeOrder: ChangeOrder): FinancialDocument

    final override fun equals(other: Any?): Boolean =
        this === other ||
            other is FinancialDocument &&
            other.javaClass == javaClass &&
            other.id == id &&
            other.version == version &&
            other.previousVersion == previousVersion &&
            other.lineItems == lineItems

    final override fun hashCode(): Int {
        var result = javaClass.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + lineItems.hashCode()
        return result
    }

    final override fun toString(): String {
        val stage =
            when (this) {
                is Estimate -> "Estimate"
                is Quote -> "Quote"
                is Invoice -> "Invoice"
            }
        return "$stage(id=$id, version=$version, previousVersion=$previousVersion, " +
            "lineItems=$lineItems, subtotal=$subtotal, taxAmount=$taxAmount, total=$total)"
    }

    /**
     * A preliminary, non-binding statement of expected charges.
     *
     * An estimate can be revised any number of times with [changeOrder]; each revision is a
     * new `Estimate` snapshot. It moves forward only by [toQuote]. An estimate cannot become
     * an invoice directly.
     */
    class Estimate private constructor(
        id: UUID,
        version: Version,
        previousVersion: Version?,
        lineItems: List<LineItem>,
    ) : FinancialDocument(id, version, previousVersion, lineItems) {
        override fun changeOrder(changeOrder: ChangeOrder): Estimate =
            Estimate(id, version.next(), version, lineItems.applying(changeOrder))

        /**
         * Issues this estimate as a quote.
         *
         * The quote is the next snapshot of the same lineage: same [id] and line items, the
         * next [version], and [previousVersion] equal to this estimate's version. This
         * estimate is not modified.
         */
        fun toQuote(): Quote = Quote.successorOf(this)

        companion object {
            /**
             * Starts a new lineage as an estimate, at [Version.INITIAL] with no previous
             * version.
             *
             * [lineItems] is copied, so later changes to the caller's list do not affect the
             * estimate.
             *
             * @throws IllegalArgumentException if [lineItems] is empty, mixes currencies, or
             * repeats a line item id.
             */
            @JvmStatic
            fun create(
                id: UUID,
                lineItems: List<LineItem>,
            ): Estimate = Estimate(id, Version.INITIAL, null, lineItems)

            /**
             * Reconstructs a previously produced estimate snapshot, for use by persistence
             * adapters and [FinancialDocumentHistory] implementations only.
             *
             * The previous version is derived from [version]. Do not use this to create new
             * snapshots: start lineages with [create] and derive successors with the
             * lifecycle functions, which keep versions consistent.
             */
            @JvmStatic
            fun restore(
                id: UUID,
                version: Version,
                lineItems: List<LineItem>,
            ): Estimate = Estimate(id, version, version.previous(), lineItems)
        }
    }

    /**
     * A formal offer to provide the listed items at the stated amounts.
     *
     * A quote is reached from an [Estimate] by [Estimate.toQuote], or starts a lineage
     * directly with [create] when the application does not use estimates. It can be revised
     * any number of times with [changeOrder], and moves forward only by [toInvoice].
     */
    class Quote private constructor(
        id: UUID,
        version: Version,
        previousVersion: Version?,
        lineItems: List<LineItem>,
    ) : FinancialDocument(id, version, previousVersion, lineItems) {
        override fun changeOrder(changeOrder: ChangeOrder): Quote = Quote(id, version.next(), version, lineItems.applying(changeOrder))

        /**
         * Issues this quote as an invoice.
         *
         * The invoice is the next snapshot of the same lineage: same [id] and line items, the
         * next [version], and [previousVersion] equal to this quote's version. This quote is
         * not modified.
         */
        fun toInvoice(): Invoice = Invoice.successorOf(this)

        companion object {
            /**
             * Starts a new lineage as a quote, at [Version.INITIAL] with no previous version,
             * for applications that issue quotes without a preceding estimate.
             *
             * [lineItems] is copied, so later changes to the caller's list do not affect the
             * quote.
             *
             * @throws IllegalArgumentException if [lineItems] is empty, mixes currencies, or
             * repeats a line item id.
             */
            @JvmStatic
            fun create(
                id: UUID,
                lineItems: List<LineItem>,
            ): Quote = Quote(id, Version.INITIAL, null, lineItems)

            /**
             * Reconstructs a previously produced quote snapshot, for use by persistence
             * adapters and [FinancialDocumentHistory] implementations only.
             *
             * The previous version is derived from [version]. Do not use this to create new
             * snapshots: start lineages with [create] and derive successors with the
             * lifecycle functions, which keep versions consistent.
             */
            @JvmStatic
            fun restore(
                id: UUID,
                version: Version,
                lineItems: List<LineItem>,
            ): Quote = Quote(id, version, version.previous(), lineItems)

            @JvmSynthetic
            internal fun successorOf(estimate: Estimate): Quote =
                Quote(estimate.id, estimate.version.next(), estimate.version, estimate.lineItems)
        }
    }

    /**
     * A request for payment of the listed items at the stated amounts.
     *
     * An invoice is reached from a [Quote] by [Quote.toInvoice], or starts a lineage directly
     * with [create], for example at a point of sale. It can be revised any number of times
     * with [changeOrder] and is the final stage: it has no further lifecycle transition.
     *
     * An invoice describes what is owed, not whether or how it has been paid. Payment
     * status, amounts paid, and balances are deliberately absent. They are derived outside
     * the document, by the `io.github.castab.commerce.payment` domain.
     */
    class Invoice private constructor(
        id: UUID,
        version: Version,
        previousVersion: Version?,
        lineItems: List<LineItem>,
    ) : FinancialDocument(id, version, previousVersion, lineItems) {
        override fun changeOrder(changeOrder: ChangeOrder): Invoice = Invoice(id, version.next(), version, lineItems.applying(changeOrder))

        companion object {
            /**
             * Starts a new lineage as an invoice, at [Version.INITIAL] with no previous
             * version. This is a first-class entry point, for example for a point-of-sale
             * purchase that never had an estimate or a quote.
             *
             * [lineItems] is copied, so later changes to the caller's list do not affect the
             * invoice.
             *
             * @throws IllegalArgumentException if [lineItems] is empty, mixes currencies, or
             * repeats a line item id.
             */
            @JvmStatic
            fun create(
                id: UUID,
                lineItems: List<LineItem>,
            ): Invoice = Invoice(id, Version.INITIAL, null, lineItems)

            /**
             * Reconstructs a previously produced invoice snapshot, for use by persistence
             * adapters and [FinancialDocumentHistory] implementations only.
             *
             * The previous version is derived from [version]. Do not use this to create new
             * snapshots: start lineages with [create] and derive successors with the
             * lifecycle functions, which keep versions consistent.
             */
            @JvmStatic
            fun restore(
                id: UUID,
                version: Version,
                lineItems: List<LineItem>,
            ): Invoice = Invoice(id, version, version.previous(), lineItems)

            @JvmSynthetic
            internal fun successorOf(quote: Quote): Invoice = Invoice(quote.id, quote.version.next(), quote.version, quote.lineItems)
        }
    }
}

/**
 * Applies [changeOrder] to a working copy of these line items and returns the result.
 * Throws on the first change that cannot be applied, so a failing change order never
 * yields a successor. Replacements keep their position; additions are appended.
 */
private fun List<LineItem>.applying(changeOrder: ChangeOrder): List<LineItem> {
    val working = LinkedHashMap<UUID, LineItem>()
    forEach { working[it.id] = it }
    changeOrder.changes.forEach { change ->
        when (change) {
            is Change.AddLineItem -> {
                val lineItem = change.lineItem
                require(lineItem.id !in working) { "Cannot add line item ${lineItem.id}: the id is already present" }
                working[lineItem.id] = lineItem
            }

            is Change.ReplaceLineItem -> {
                require(change.lineItemId in working) { "Cannot replace line item ${change.lineItemId}: no such line item" }
                working[change.lineItemId] = change.replacement
            }

            is Change.RemoveLineItem -> {
                require(working.remove(change.lineItemId) != null) {
                    "Cannot remove line item ${change.lineItemId}: no such line item"
                }
            }
        }
    }
    return working.values.toList()
}

/** A read-only copy that neither the caller's list nor a cast to `MutableList` can change. */
@JvmSynthetic
internal fun <T> List<T>.toImmutableList(): List<T> = Collections.unmodifiableList(ArrayList(this))
