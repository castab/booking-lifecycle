package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.runtime.testing.FinancialLedgerFixture
import io.github.castab.commerce.runtime.testing.depositMoney
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.util.UUID

class DepositRequirementSchemaSpec :
    FunSpec({
        lateinit var fixture: FinancialLedgerFixture
        beforeSpec { fixture = FinancialLedgerFixture() }
        afterSpec { fixture.close() }

        fun insert(
            id: UUID,
            overrides: Map<String, Any?>,
        ) {
            val values =
                linkedMapOf<String, Any?>(
                    "document_id" to id,
                    "revision" to 1,
                    "previous_revision" to null,
                    "kind" to "ACTIVE",
                    "approval_version" to 1,
                    "terms_kind" to "FIXED",
                    "terms_amount" to "25.00",
                    "terms_scale" to 2,
                    "required_amount" to "25.00",
                    "required_scale" to 2,
                    "currency" to "USD",
                )
            values.putAll(overrides)
            val bindings = values.keys.joinToString { if (it.endsWith("amount")) "CAST(:$it AS numeric)" else ":$it" }
            fixture.transactor.inTransaction {
                it.handle
                    .createUpdate("INSERT INTO commerce.deposit_requirement_revisions (${values.keys.joinToString()}) VALUES ($bindings)")
                    .bindMap(values)
                    .execute()
            }
        }

        listOf(
            "nonpositive revision" to mapOf("revision" to 0),
            "missing predecessor" to mapOf("revision" to 2, "previous_revision" to 1),
            "null successor predecessor" to mapOf("revision" to 2),
            "gap" to mapOf("revision" to 3, "previous_revision" to 1),
            "first revision predecessor" to mapOf("previous_revision" to 1),
            "missing snapshot" to mapOf("approval_version" to 2),
            "unknown kind" to mapOf("kind" to "SATISFIED"),
            "unknown terms" to mapOf("terms_kind" to "DEFAULT"),
            "missing terms" to mapOf("terms_kind" to null),
            "missing amount" to mapOf("required_amount" to null),
            "zero amount" to mapOf("required_amount" to "0"),
            "negative amount" to mapOf("terms_amount" to "-1"),
            "NaN amount" to mapOf("required_amount" to "NaN"),
            "infinite amount" to mapOf("terms_amount" to "Infinity"),
            "invalid percentage" to mapOf("terms_kind" to "PERCENTAGE", "terms_amount" to "101"),
            "invalid currency" to mapOf("currency" to "usd"),
            "withdrawal with terms" to mapOf("kind" to "WITHDRAWN"),
        ).forEach { (what, overrides) ->
            test("schema rejects $what without writing a revision") {
                val document = fixture.document()
                shouldThrow<UnableToExecuteStatementException> { insert(document.id, overrides) }
                fixture.ledger.depositRequirementHistory(document.id).size shouldBe 0
            }
        }

        test("schema rejects withdrawal after withdrawal and duplicate successors") {
            val document = fixture.document()
            fixture.ledger.activateDepositRequirement(document.id, document.version, DepositTerms.Fixed(depositMoney("25")), null)
            fixture.ledger.withdrawDepositRequirement(document.id, DepositRequirementRevision.INITIAL)
            val withdrawal =
                mapOf<String, Any?>(
                    "kind" to "WITHDRAWN",
                    "revision" to 3,
                    "previous_revision" to 2,
                    "approval_version" to null,
                    "terms_kind" to null,
                    "terms_amount" to null,
                    "terms_scale" to null,
                    "required_amount" to null,
                    "required_scale" to null,
                    "currency" to null,
                )
            shouldThrow<UnableToExecuteStatementException> { insert(document.id, withdrawal) }
            shouldThrow<UnableToExecuteStatementException> { insert(document.id, mapOf("revision" to 2, "previous_revision" to 1)) }
            fixture.ledger.depositRequirementHistory(document.id).size shouldBe 2
        }
    })
