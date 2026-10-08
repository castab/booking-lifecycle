package io.github.castab.commerce.runtime.financial

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.runtime.testing.FinancialLedgerFixture
import io.github.castab.commerce.runtime.testing.depositMoney
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.time.OffsetDateTime

class DepositRequirementRestorationSpec :
    FunSpec({
        lateinit var fixture: FinancialLedgerFixture
        beforeSpec { fixture = FinancialLedgerFixture() }
        afterSpec { fixture.close() }

        test("approval document and requirement timestamps come from their own persisted facts") {
            val document = fixture.document()
            val snapshotTime = Instant.parse("2000-01-01T00:00:00Z")
            // Deliberately distinct fixture timestamps, not a clock or sleep based assertion.
            fixture.transactor.inTransaction { transaction ->
                transaction.handle
                    .createUpdate(
                        "UPDATE commerce.financial_document_snapshots SET created_at = :time WHERE document_id = :id",
                    ).bind("time", OffsetDateTime.parse("2000-01-01T00:00:00Z"))
                    .bind("id", document.id)
                    .execute()
            }
            fixture.ledger.activateDepositRequirement(document.id, document.version, DepositTerms.Fixed(depositMoney("25")), null)
            val requirementTime =
                fixture.transactor.inTransaction { transaction ->
                    transaction.handle
                        .createQuery("SELECT created_at FROM commerce.deposit_requirement_revisions WHERE document_id = :id")
                        .bind("id", document.id)
                        .mapTo(OffsetDateTime::class.java)
                        .one()
                        .toInstant()
                }
            requirementTime shouldNotBe snapshotTime
            fixture.ledger.version(document.reference).createdAt shouldBe snapshotTime
            val latest = fixture.ledger.latestDepositRequirement(document.id)!!
            latest.createdAt shouldBe requirementTime
            latest.requirement.shouldBeInstanceOf<DepositRequirement.Active>().approvalReference shouldBe document.reference
            fixture.ledger
                .depositRequirementHistory(document.id)
                .single()
                .createdAt shouldBe requirementTime
            val view = fixture.ledger.financialLineages(listOf(document.id)).single()
            view.latestVersion.createdAt shouldBe snapshotTime
            view.depositRequirement!!.createdAt shouldBe requirementTime
            // The join deliberately restores only the document, with no created_at column.
            fixture.transactor.inTransaction { transaction ->
                transaction.handle
                    .createQuery(
                        "SELECT document_id, version, stage, lines::text AS lines FROM commerce.financial_document_snapshots WHERE document_id = :id",
                    ).bind("id", document.id)
                    .map { rows, _ -> fixture.documents.restoreDocument(rows) }
                    .one() shouldBe document
            }
        }

        test("document-only approval restoration retains strict stored-document validation on every deposit read") {
            val document = fixture.document()
            fixture.ledger.activateDepositRequirement(document.id, document.version, DepositTerms.Fixed(depositMoney("25")), null)
            // Advance so the latest snapshot is valid; only the original approval is corrupt.
            fixture.ledger.issueInvoice(document.id, expectedDocumentVersion = document.version)
            fixture.transactor.inTransaction { transaction ->
                transaction.handle
                    .createUpdate(
                        "UPDATE commerce.financial_document_snapshots SET lines = '[]'::jsonb WHERE document_id = :id AND version = 1",
                    ).bind("id", document.id)
                    .execute()
            }
            shouldThrow<IllegalStateException> { fixture.ledger.latestDepositRequirement(document.id) }
            shouldThrow<IllegalStateException> { fixture.ledger.depositRequirementHistory(document.id) }
            shouldThrow<IllegalStateException> { fixture.ledger.financialLineages(listOf(document.id)) }
        }
    })
