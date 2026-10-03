package io.github.castab.commerce.runtime.financial

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.testing.FinancialLedgerFixture
import io.github.castab.commerce.runtime.testing.depositMoney
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private val initial = DepositRequirementRevision.INITIAL

private fun fixed(value: String = "25.00") = DepositTerms.Fixed(depositMoney(value))

class DepositRequirementLedgerSpec :
    FunSpec({
        lateinit var fixture: FinancialLedgerFixture
        beforeSpec { fixture = FinancialLedgerFixture() }
        afterSpec { fixture.close() }

        test("first activation replacement withdrawal and reactivation retain immutable timestamped history") {
            val ledger = fixture.ledger
            val document = fixture.document()
            ledger.latestDepositRequirement(document.id) shouldBe null
            ledger.depositRequirementHistory(document.id).shouldBeEmpty()
            val first = ledger.activateDepositRequirement(document.id, document.version, fixed(), null)
            val second =
                ledger.activateDepositRequirement(
                    document.id,
                    document.version,
                    DepositTerms.Percentage(BigDecimal("50.000")),
                    initial,
                )
            val third = ledger.withdrawDepositRequirement(document.id, second.requirement.revision)
            third.requirement.shouldBeInstanceOf<DepositRequirement.Withdrawn>()
            ledger.latestDepositRequirement(document.id)!!.requirement shouldBe third.requirement
            val fourth = ledger.activateDepositRequirement(document.id, document.version, fixed("10"), third.requirement.revision)
            val history = ledger.depositRequirementHistory(document.id)
            history.map { it.requirement }.shouldContainExactly(
                first.requirement,
                second.requirement,
                third.requirement,
                fourth.requirement,
            )
            history.map { it.createdAt }.shouldContainExactly(first.createdAt, second.createdAt, third.createdAt, fourth.createdAt)
            history.map { it.requirement.revision.number }.shouldContainExactly(1, 2, 3, 4)
            shouldThrow<UnsupportedOperationException> { (history as MutableList).clear() }
            first.requirement.shouldBeInstanceOf<DepositRequirement.Active>().requiredAmount shouldBe depositMoney("25.00")
        }

        test("exact fixed and percentage decimal scales including negative scales survive persistence") {
            listOf(
                fixed("25.0000"),
                fixed("2E+1"),
                DepositTerms.Percentage(BigDecimal("2.50000E+1")),
                DepositTerms.Percentage(BigDecimal("1E+1")),
            ).forEach { terms ->
                val document = fixture.document()
                val stored = fixture.ledger.activateDepositRequirement(document.id, document.version, terms, null)
                fixture.ledger.latestDepositRequirement(document.id)!!.requirement shouldBe stored.requirement
            }
        }

        test("stale document version fails without creating history") {
            val document = fixture.document()
            fixture.ledger.issueInvoice(document.id)
            shouldThrow<CommerceFailure.Conflict> {
                fixture.ledger.activateDepositRequirement(
                    document.id,
                    document.version,
                    fixed(),
                    null,
                )
            }
            fixture.ledger.depositRequirementHistory(document.id).shouldBeEmpty()
        }

        test("strict expected requirement tokens cover Active and Withdrawn history") {
            val document = fixture.document()
            val ledger = fixture.ledger
            ledger.activateDepositRequirement(document.id, document.version, fixed(), null)
            shouldThrow<CommerceFailure.Conflict> { ledger.activateDepositRequirement(document.id, document.version, fixed(), null) }
            shouldThrow<CommerceFailure.Conflict> {
                ledger.activateDepositRequirement(
                    document.id,
                    document.version,
                    fixed(),
                    initial.next(),
                )
            }
            val withdrawn = ledger.withdrawDepositRequirement(document.id, initial)
            shouldThrow<CommerceFailure.Conflict> { ledger.activateDepositRequirement(document.id, document.version, fixed(), null) }
            shouldThrow<CommerceFailure.Conflict> { ledger.activateDepositRequirement(document.id, document.version, fixed(), initial) }
            shouldThrow<CommerceFailure.Conflict> { ledger.withdrawDepositRequirement(document.id, initial) }
            shouldThrow<CommerceFailure.IllegalTransition> {
                ledger.withdrawDepositRequirement(
                    document.id,
                    withdrawn.requirement.revision,
                )
            }
            ledger.depositRequirementHistory(document.id).size shouldBe 2
        }

        test("withdrawal is independent of subsequent document versions") {
            val document = fixture.document()
            fixture.ledger.activateDepositRequirement(document.id, document.version, fixed(), null)
            fixture.ledger.issueInvoice(document.id)
            fixture.ledger
                .withdrawDepositRequirement(document.id, initial)
                .requirement
                .shouldBeInstanceOf<DepositRequirement.Withdrawn>()
        }

        test("missing lineage and absent requirement report NotFound") {
            val ledger = fixture.ledger
            val missing = UUID.randomUUID()
            shouldThrow<CommerceFailure.NotFound> { ledger.activateDepositRequirement(missing, Version.INITIAL, fixed(), null) }
            shouldThrow<CommerceFailure.NotFound> { ledger.latestDepositRequirement(missing) }
            shouldThrow<CommerceFailure.NotFound> { ledger.depositRequirementHistory(missing) }
            shouldThrow<CommerceFailure.NotFound> { ledger.withdrawDepositRequirement(missing, initial) }
            shouldThrow<CommerceFailure.NotFound> { ledger.withdrawDepositRequirement(fixture.document().id, initial) }
        }

        test("invalid approval leaves no revision") {
            val document = fixture.document()
            shouldThrow<CommerceFailure.ValidationFailed> {
                fixture.ledger.activateDepositRequirement(document.id, document.version, fixed("101"), null)
            }
            shouldThrow<CommerceFailure.ValidationFailed> {
                fixture.ledger.activateDepositRequirement(
                    document.id,
                    document.version,
                    DepositTerms.Fixed(depositMoney("10", "EUR")),
                    null,
                )
            }
            fixture.ledger.depositRequirementHistory(document.id).shouldBeEmpty()
        }

        test("caller-owned transactions see their own writes and roll back the entire stream with application rows") {
            val ledger = fixture.ledger
            val document = fixture.document()
            val applicationId = UUID.randomUUID()
            shouldThrow<IllegalStateException> {
                fixture.transactor.inTransaction { transaction ->
                    transaction.handle
                        .createUpdate("INSERT INTO testapp.test_application_records (id, value) VALUES (:id, 'joined')")
                        .bind("id", applicationId)
                        .execute()
                    ledger.activateDepositRequirement(transaction, document.id, document.version, fixed(), null)
                    ledger.latestDepositRequirement(transaction, document.id)!!.requirement.revision shouldBe initial
                    ledger.withdrawDepositRequirement(transaction, document.id, initial)
                    ledger
                        .financialLineages(transaction, listOf(document.id))
                        .single()
                        .depositRequirement!!
                        .requirement
                        .shouldBeInstanceOf<DepositRequirement.Withdrawn>()
                    ledger.depositRequirementHistory(transaction, document.id).size shouldBe 2
                    fixture.onOtherThread { ledger.latestDepositRequirement(document.id) } shouldBe null
                    error("abort")
                }
            }
            ledger.depositRequirementHistory(document.id).shouldBeEmpty()
            fixture.transactor.inTransaction {
                it.handle
                    .createQuery("SELECT count(*) FROM testapp.test_application_records WHERE id = :id")
                    .bind("id", applicationId)
                    .mapTo(Int::class.java)
                    .one() shouldBe 0
                ledger.activateDepositRequirement(it, document.id, document.version, fixed(), null)
            }
            ledger.depositRequirementHistory(document.id).size shouldBe 1
        }

        test("later totals leave approved percentage frozen and satisfaction follows refunds") {
            val ledger = fixture.ledger
            val document = fixture.document()
            val first = ledger.activateDepositRequirement(document.id, document.version, DepositTerms.Percentage(BigDecimal("25")), null)
            val originalLine = document.lineItems.single()
            val changed =
                ledger.changeOrder(
                    document.id,
                    ChangeOrder(
                        listOf(ChangeOrder.Change.ReplaceLineItem(originalLine.id, originalLine.copy(price = depositMoney("200")))),
                    ),
                )
            val frozen = ledger.latestDepositRequirement(document.id)!!.requirement.shouldBeInstanceOf<DepositRequirement.Active>()
            frozen.requiredAmount shouldBe depositMoney("25.00")
            frozen.approvalReference shouldBe document.reference
            val payment = fixture.payment()
            val allocation = ledger.allocatePayment(payment.id, UUID.randomUUID(), changed.reference, depositMoney("25"), Instant.EPOCH)
            ledger.financialLineages(listOf(document.id)).single().depositSatisfied shouldBe true
            ledger.recordRefund(
                payment.id,
                UUID.randomUUID(),
                depositMoney("1"),
                PaymentMethod.CASH,
                Instant.EPOCH,
                null,
                listOf(RefundAllocationPortion(UUID.randomUUID(), allocation.id, depositMoney("1"), Instant.EPOCH)),
            )
            ledger.financialLineages(listOf(document.id)).single().depositSatisfied shouldBe false
            ledger.depositRequirementHistory(document.id).map { it.requirement }.shouldContainExactly(first.requirement)
        }

        test("malformed frozen amounts currency and original terms fail loudly on restoration") {
            listOf("required_amount = 26", "currency = 'ZZZ'", "terms_scale = -1").forEach { change ->
                val document = fixture.document()
                val ledger = fixture.ledger
                ledger.activateDepositRequirement(document.id, document.version, fixed(), null)
                fixture.transactor.inTransaction { transaction ->
                    transaction.handle.execute(
                        "ALTER TABLE commerce.deposit_requirement_revisions DISABLE TRIGGER deposit_requirement_immutable",
                    )
                    transaction.handle
                        .createUpdate("UPDATE commerce.deposit_requirement_revisions SET $change WHERE document_id = :id")
                        .bind("id", document.id)
                        .execute()
                    transaction.handle.execute(
                        "ALTER TABLE commerce.deposit_requirement_revisions ENABLE TRIGGER deposit_requirement_immutable",
                    )
                }
                shouldThrow<IllegalStateException> { ledger.latestDepositRequirement(document.id) }
                shouldThrow<IllegalStateException> { ledger.financialLineages(listOf(document.id)) }
            }
        }

        test("database rejects edits and deletions of revisions") {
            val document = fixture.document()
            fixture.ledger.activateDepositRequirement(document.id, document.version, fixed(), null)
            listOf(
                "UPDATE commerce.deposit_requirement_revisions SET required_amount = 26",
                "DELETE FROM commerce.deposit_requirement_revisions",
            ).forEach { sql ->
                shouldThrow<UnableToExecuteStatementException> {
                    fixture.transactor.inTransaction {
                        it.handle
                            .createUpdate(
                                "$sql WHERE document_id = :id",
                            ).bind("id", document.id)
                            .execute()
                    }
                }
            }
            fixture.ledger
                .latestDepositRequirement(
                    document.id,
                )!!
                .requirement
                .shouldBeInstanceOf<DepositRequirement.Active>()
                .requiredAmount shouldBe
                depositMoney("25.00")
        }

        listOf(false, true).forEach { replacement ->
            test("concurrent ${if (replacement) "replacement" else "first activation"} has one successor and a Conflict loser") {
                val document = fixture.document()
                val ledger = fixture.ledger
                val expected =
                    if (replacement) {
                        ledger.activateDepositRequirement(document.id, document.version, fixed(), null)
                        initial
                    } else {
                        null
                    }
                val outcome =
                    fixture.serialize(
                        holding = { ledger.activateDepositRequirement(it, document.id, document.version, fixed("30"), expected) },
                        waiting = { ledger.activateDepositRequirement(it, document.id, document.version, fixed("40"), expected) },
                    )
                outcome.exceptionOrNull().shouldBeInstanceOf<CommerceFailure.Conflict>()
                ledger.depositRequirementHistory(document.id).size shouldBe if (replacement) 2 else 1
                ledger
                    .latestDepositRequirement(
                        document.id,
                    )!!
                    .requirement
                    .shouldBeInstanceOf<DepositRequirement.Active>()
                    .requiredAmount shouldBe
                    depositMoney("30")
            }
        }

        test("competing activation sees a rolled back first writer as no history") {
            val document = fixture.document()
            val outcome =
                fixture.serialize(
                    holding = { fixture.ledger.activateDepositRequirement(it, document.id, document.version, fixed("30"), null) },
                    waiting = { fixture.ledger.activateDepositRequirement(it, document.id, document.version, fixed("40"), null) },
                    rollbackHolder = true,
                )
            outcome.getOrThrow().requirement.revision shouldBe initial
            fixture.ledger.depositRequirementHistory(document.id).size shouldBe 1
        }

        test("concurrent withdrawals append exactly one withdrawal") {
            val document = fixture.document()
            fixture.ledger.activateDepositRequirement(document.id, document.version, fixed(), null)
            val outcome =
                fixture.serialize(
                    holding = { fixture.ledger.withdrawDepositRequirement(it, document.id, initial) },
                    waiting = { fixture.ledger.withdrawDepositRequirement(it, document.id, initial) },
                )
            outcome.exceptionOrNull().shouldBeInstanceOf<CommerceFailure.Conflict>()
            fixture.ledger.depositRequirementHistory(document.id).size shouldBe 2
        }

        test("approval waits for a document successor then rejects its stale expected document version") {
            val document = fixture.document()
            val outcome =
                fixture.serialize(
                    holding = { fixture.ledger.issueInvoice(it, document.id) },
                    waiting = { fixture.ledger.activateDepositRequirement(it, document.id, document.version, fixed(), null) },
                )
            outcome.exceptionOrNull().shouldBeInstanceOf<CommerceFailure.Conflict>()
            fixture.ledger.depositRequirementHistory(document.id).shouldBeEmpty()
        }

        test("lineage mutation lock is compatible with payment foreign-key checks and nonlocking reads") {
            val document = fixture.document()
            val payment = fixture.payment()
            fixture.transactor.inTransaction { transaction ->
                fixture.ledger.activateDepositRequirement(transaction, document.id, document.version, fixed(), null)
                fixture.onOtherThread {
                    fixture.ledger.allocatePayment(payment.id, UUID.randomUUID(), document.reference, depositMoney("25"), Instant.EPOCH)
                    fixture.ledger
                        .financialLineages(listOf(document.id))
                        .single()
                        .depositRequirement
                } shouldBe null
            }
            fixture.ledger
                .financialLineages(listOf(document.id))
                .single()
                .depositSatisfied shouldBe true
        }
        test("a repeatable read mutation rejects a document successor committed after its snapshot began") {
            val document = fixture.document()
            shouldThrow<CommerceFailure.Conflict> {
                fixture.transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
                    fixture.ledger.latest(transaction, document.id) shouldBe document
                    fixture.onOtherThread { fixture.ledger.issueInvoice(document.id) }
                    fixture.ledger.activateDepositRequirement(transaction, document.id, document.version, fixed(), null)
                }
            }
            fixture.ledger.depositRequirementHistory(document.id).shouldBeEmpty()
        }

        listOf(false, true).forEach { replacement ->
            test("repeatable read rejects a competing ${if (replacement) "replacement" else "first activation"} after its snapshot began") {
                val document = fixture.document()
                val expected =
                    if (replacement) {
                        fixture.ledger.activateDepositRequirement(document.id, document.version, fixed(), null)
                        initial
                    } else {
                        null
                    }
                shouldThrow<CommerceFailure.Conflict> {
                    fixture.transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
                        fixture.ledger
                            .latestDepositRequirement(transaction, document.id)
                            ?.requirement
                            ?.revision shouldBe expected
                        fixture.onOtherThread {
                            fixture.ledger.activateDepositRequirement(
                                document.id,
                                document.version,
                                fixed("30"),
                                expected,
                            )
                        }
                        fixture.ledger.activateDepositRequirement(transaction, document.id, document.version, fixed("40"), expected)
                    }
                }
                fixture.ledger.depositRequirementHistory(document.id).size shouldBe if (replacement) 2 else 1
            }
        }
    })
