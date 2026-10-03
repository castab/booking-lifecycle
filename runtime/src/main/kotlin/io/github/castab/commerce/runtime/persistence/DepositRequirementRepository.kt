package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.runtime.financial.DepositRequirementVersion
import io.github.castab.commerce.runtime.operation.CommerceFailure
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.Currency
import java.util.UUID

/** Internal append-only storage; consumers use FinancialLedger. Reads validate the original approval snapshot. */
internal class DepositRequirementRepository(
    private val documents: PostgresFinancialDocumentRepository,
) {
    private val select =
        """SELECT r.document_id, r.revision, r.previous_revision, r.kind, r.approval_version,
                  r.terms_kind, r.terms_amount, r.terms_scale, r.required_amount, r.required_scale,
                  r.currency, r.created_at AS requirement_created_at, d.version, d.stage, d.lines::text AS lines,
                  p.kind AS predecessor_kind
           FROM commerce.deposit_requirement_revisions r
           LEFT JOIN commerce.financial_document_snapshots d
             ON d.document_id = r.document_id AND d.version = r.approval_version
           LEFT JOIN commerce.deposit_requirement_revisions p
             ON p.document_id = r.document_id AND p.revision = r.previous_revision"""

    fun latest(
        transaction: Transaction,
        id: UUID,
    ): DepositRequirementVersion? =
        transaction.handle
            .createQuery("$select WHERE r.document_id = :id ORDER BY r.revision DESC LIMIT 1")
            .bind("id", id)
            .map { rows, _ -> restore(rows) }
            .findOne()
            .orElse(null)

    fun history(
        transaction: Transaction,
        id: UUID,
    ): List<DepositRequirementVersion> =
        transaction.handle
            .createQuery("$select WHERE r.document_id = :id ORDER BY r.revision")
            .bind("id", id)
            .map { rows, _ -> restore(rows) }
            .list()

    fun latestForLineages(
        transaction: Transaction,
        ids: Collection<UUID>,
    ): Map<UUID, DepositRequirementVersion> =
        transaction.handle
            .createQuery(
                """$select WHERE r.document_id IN (<ids>)
               AND NOT EXISTS (SELECT 1 FROM commerce.deposit_requirement_revisions newer
                               WHERE newer.document_id = r.document_id AND newer.revision > r.revision)""",
            ).bindList("ids", ids)
            .map { rows, _ -> restore(rows) }
            .list()
            .associateBy { it.requirement.documentId }

    fun insert(
        transaction: Transaction,
        requirement: DepositRequirement,
    ): DepositRequirementVersion {
        val active = requirement as? DepositRequirement.Active
        val terms = active?.terms
        val value =
            when (terms) {
                is DepositTerms.Fixed -> terms.amount.amount
                is DepositTerms.Percentage -> terms.percentage
                null -> null
            }
        try {
            val createdAt =
                transaction.handle
                    .createQuery(
                        """INSERT INTO commerce.deposit_requirement_revisions
                   (document_id, revision, previous_revision, kind, approval_version,
                    terms_kind, terms_amount, terms_scale, required_amount, required_scale, currency)
                   VALUES (:id, :revision, :previous, :kind, :approval,
                           :termsKind, :value, :termsScale, :amount, :requiredScale, :currency)
                   RETURNING created_at""",
                    ).bind("id", requirement.documentId)
                    .bind("revision", requirement.revision.number)
                    .bind("previous", requirement.previousRevision?.number)
                    .bind("kind", if (active == null) "WITHDRAWN" else "ACTIVE")
                    .bind("approval", active?.approvalReference?.version?.number)
                    .bind(
                        "termsKind",
                        when (terms) {
                            is DepositTerms.Fixed -> "FIXED"
                            is DepositTerms.Percentage -> "PERCENTAGE"
                            null -> null
                        },
                    ).bind("value", value)
                    .bind("termsScale", value?.scale())
                    .bind("amount", active?.requiredAmount?.amount)
                    .bind("requiredScale", active?.requiredAmount?.amount?.scale())
                    .bind("currency", active?.requiredAmount?.currency?.currencyCode)
                    .mapTo(OffsetDateTime::class.java)
                    .one()
                    .toInstant()
            return DepositRequirementVersion.from(requirement, createdAt)
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) {
                throw CommerceFailure.Conflict(
                    "Deposit requirement for ${requirement.documentId} has a competing successor",
                    e,
                )
            }
            throw e
        }
    }

    private fun restore(rows: ResultSet): DepositRequirementVersion {
        val id = rows.getObject("document_id", UUID::class.java)
        val what = "deposit requirement $id revision ${rows.getInt("revision")}"
        try {
            val revision = DepositRequirementRevision.of(rows.getInt("revision"))
            val previous = rows.getObject("previous_revision", Int::class.javaObjectType)
            require(previous == revision.previous?.number) { "Invalid predecessor sequence" }
            if (previous != null) require(rows.getString("predecessor_kind") in setOf("ACTIVE", "WITHDRAWN")) { "Missing predecessor" }
            val requirement =
                when (rows.getString("kind")) {
                    "ACTIVE" -> {
                        require(rows.getObject("approval_version") != null) { "Missing approval snapshot" }
                        val currency = Currency.getInstance(rows.getString("currency"))
                        val value = decimal(rows, "terms_amount", "terms_scale")
                        val terms =
                            when (rows.getString("terms_kind")) {
                                "FIXED" -> DepositTerms.Fixed(Money(value, currency))
                                "PERCENTAGE" -> DepositTerms.Percentage(value)
                                else -> error("Unsupported deposit terms kind")
                            }
                        val approval = documents.restoreDocument(rows)
                        DepositRequirement.Active.restore(
                            approval,
                            revision,
                            terms,
                            Money(decimal(rows, "required_amount", "required_scale"), currency),
                        )
                    }
                    "WITHDRAWN" -> {
                        require(rows.getString("predecessor_kind") == "ACTIVE") { "A withdrawal must follow Active" }
                        listOf(
                            "approval_version",
                            "terms_kind",
                            "terms_amount",
                            "terms_scale",
                            "required_amount",
                            "required_scale",
                            "currency",
                        ).forEach { require(rows.getObject(it) == null) { "Withdrawal contains $it" } }
                        DepositRequirement.Withdrawn.restore(id, revision)
                    }
                    else -> error("Unsupported deposit requirement kind")
                }
            return DepositRequirementVersion.from(
                requirement,
                rows.getObject("requirement_created_at", OffsetDateTime::class.java).toInstant(),
            )
        } catch (e: RuntimeException) {
            throw IllegalStateException("Persisted $what violates its representation or domain invariants: ${e.message}", e)
        }
    }

    private fun decimal(
        rows: ResultSet,
        value: String,
        scale: String,
    ): BigDecimal {
        val exactScale = requireNotNull(rows.getObject(scale, Int::class.javaObjectType)) { "Missing $scale" }
        return requireNotNull(rows.getBigDecimal(value)) { "Missing $value" }.setScale(exactScale, RoundingMode.UNNECESSARY)
    }
}
