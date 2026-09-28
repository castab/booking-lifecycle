package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentHistory
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.operation.CommerceFailure
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.sql.ResultSet
import java.util.Currency
import java.util.UUID

/** Append-only financial snapshots in a caller-owned transaction. */
interface FinancialDocumentRepository {
    fun insert(
        transaction: Transaction,
        snapshot: FinancialDocument,
    )

    fun retrieveVersion(
        transaction: Transaction,
        reference: FinancialDocumentReference,
    ): FinancialDocument?

    fun retrieveLatestVersion(
        transaction: Transaction,
        id: UUID,
    ): FinancialDocument?

    fun history(
        transaction: Transaction,
        id: UUID,
    ): List<FinancialDocument>

    /** Adapts transaction-bound reads to the domain history SPI. */
    fun asHistory(transaction: Transaction): FinancialDocumentHistory =
        object : FinancialDocumentHistory {
            override fun retrieveVersion(reference: FinancialDocumentReference): FinancialDocument? =
                this@FinancialDocumentRepository.retrieveVersion(transaction, reference)

            override fun retrieveLatestVersion(id: UUID): FinancialDocument? =
                this@FinancialDocumentRepository.retrieveLatestVersion(transaction, id)
        }
}

internal class PostgresFinancialDocumentRepository : FinancialDocumentRepository {
    override fun insert(
        transaction: Transaction,
        snapshot: FinancialDocument,
    ) {
        val predecessor = snapshot.previousReference?.let { retrieveVersion(transaction, it) }
        if (snapshot.previousReference != null && predecessor == null) {
            throw CommerceFailure.Conflict("Financial document ${snapshot.reference} has no stored predecessor")
        }
        if (predecessor != null && !predecessor.mayAdvanceTo(snapshot)) {
            throw CommerceFailure.Conflict("Financial document ${snapshot.reference} has an invalid stage successor")
        }
        try {
            transaction.handle
                .createUpdate(
                    """INSERT INTO commerce.financial_document_snapshots
                       (document_id, version, previous_version, stage)
                       VALUES (:id, :version, :previous, :stage)""",
                ).bind("id", snapshot.id)
                .bind("version", snapshot.version.number)
                .bind("previous", snapshot.previousVersion?.number)
                .bind("stage", snapshot.stageName())
                .execute()
            snapshot.lineItems.forEachIndexed { position, line ->
                transaction.handle
                    .createUpdate(
                        """INSERT INTO commerce.financial_document_lines
                           (document_id, version, position, line_id, description, sub_description,
                            quantity, price_amount, price_currency, tax_amount)
                           VALUES (:id, :version, :position, :lineId, :description, :subDescription,
                                   :quantity, :price, :currency, :tax)""",
                    ).bind("id", snapshot.id)
                    .bind("version", snapshot.version.number)
                    .bind("position", position)
                    .bind("lineId", line.id)
                    .bind("description", line.description)
                    .bind("subDescription", line.subDescription)
                    .bind("quantity", line.quantity)
                    .bind("price", line.price.amount)
                    .bind("currency", line.currency.currencyCode)
                    .bind("tax", line.taxAmount.amount)
                    .execute()
            }
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) {
                throw CommerceFailure.Conflict("Financial document ${snapshot.reference} already has this snapshot or successor", e)
            }
            throw e
        }
    }

    override fun retrieveVersion(
        transaction: Transaction,
        reference: FinancialDocumentReference,
    ): FinancialDocument? {
        val stage =
            transaction.handle
                .createQuery(
                    """SELECT stage FROM commerce.financial_document_snapshots
                       WHERE document_id = :id AND version = :version""",
                ).bind("id", reference.id)
                .bind("version", reference.version.number)
                .mapTo(String::class.java)
                .findOne()
        if (stage.isEmpty) return null
        val lines =
            transaction.handle
                .createQuery(
                    """SELECT line_id, description, sub_description, quantity, price_amount, price_currency, tax_amount
                       FROM commerce.financial_document_lines
                       WHERE document_id = :id AND version = :version ORDER BY position""",
                ).bind("id", reference.id)
                .bind("version", reference.version.number)
                .map { rows, _ -> line(rows) }
                .list()
        return when (stage.get()) {
            "ESTIMATE" -> FinancialDocument.Estimate.restore(reference.id, reference.version, lines)
            "QUOTE" -> FinancialDocument.Quote.restore(reference.id, reference.version, lines)
            "INVOICE" -> FinancialDocument.Invoice.restore(reference.id, reference.version, lines)
            else -> error("Unsupported financial document stage")
        }
    }

    override fun retrieveLatestVersion(
        transaction: Transaction,
        id: UUID,
    ): FinancialDocument? {
        val number =
            transaction.handle
                .createQuery("SELECT max(version) FROM commerce.financial_document_snapshots WHERE document_id = :id")
                .bind("id", id)
                .map { rows, _ -> rows.getObject(1, Integer::class.java)?.toInt() }
                .one() ?: return null
        return retrieveVersion(transaction, FinancialDocumentReference(id, Version.of(number)))
    }

    override fun history(
        transaction: Transaction,
        id: UUID,
    ): List<FinancialDocument> =
        transaction.handle
            .createQuery("SELECT version FROM commerce.financial_document_snapshots WHERE document_id = :id ORDER BY version")
            .bind("id", id)
            .map { rows, _ -> Version.of(rows.getInt(1)) }
            .list()
            .map { version -> checkNotNull(retrieveVersion(transaction, FinancialDocumentReference(id, version))) }

    private fun line(rows: ResultSet): LineItem {
        val currency = Currency.getInstance(rows.getString("price_currency").trim())
        return LineItem(
            rows.getObject("line_id", UUID::class.java),
            rows.getString("description"),
            rows.getString("sub_description"),
            rows.getBigDecimal("quantity"),
            Money(rows.getBigDecimal("price_amount"), currency),
            Money(rows.getBigDecimal("tax_amount"), currency),
        )
    }

    private fun FinancialDocument.stageName(): String =
        when (this) {
            is FinancialDocument.Estimate -> "ESTIMATE"
            is FinancialDocument.Quote -> "QUOTE"
            is FinancialDocument.Invoice -> "INVOICE"
        }

    private fun FinancialDocument.mayAdvanceTo(next: FinancialDocument): Boolean =
        when (this) {
            is FinancialDocument.Estimate -> next is FinancialDocument.Estimate || next is FinancialDocument.Quote
            is FinancialDocument.Quote -> next is FinancialDocument.Quote || next is FinancialDocument.Invoice
            is FinancialDocument.Invoice -> next is FinancialDocument.Invoice
        }
}
