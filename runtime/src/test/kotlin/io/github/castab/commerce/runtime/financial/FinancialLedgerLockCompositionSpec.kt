package io.github.castab.commerce.runtime.financial

import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.testing.FinancialLedgerFixture
import io.github.castab.commerce.runtime.testing.depositMoney
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FinancialLedgerLockCompositionSpec :
    FunSpec({
        lateinit var fixture: FinancialLedgerFixture
        beforeSpec { fixture = FinancialLedgerFixture() }
        afterSpec { fixture.close() }

        TransactionIsolation.entries.forEach { isolation ->
            test("opposite caller-owned payment and document operation orders fail at the lineage lock under $isolation") {
                val document = fixture.document()
                val payment = fixture.payment()
                val rolledBackAllocation = UUID.randomUUID()
                val committedAllocation = UUID.randomUUID()
                val paymentOwner = CompletableFuture<Int>()
                val lineageOwner = CompletableFuture<Int>()
                val attemptLineage = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val a =
                        executor.submit(
                            Callable {
                                runCatching {
                                    fixture.transactor.inTransaction(isolation) { transaction ->
                                        fixture.ledger.allocatePayment(
                                            transaction,
                                            payment.id,
                                            rolledBackAllocation,
                                            document.reference,
                                            depositMoney("25"),
                                            Instant.EPOCH,
                                        )
                                        paymentOwner.complete(fixture.pid(transaction))
                                        check(attemptLineage.await(10, TimeUnit.SECONDS))
                                        // B owns the lineage and is observably waiting for A's payment.
                                        fixture.ledger.issueInvoice(transaction, document.id)
                                    }
                                }.also { if (!paymentOwner.isDone) paymentOwner.completeExceptionally(it.exceptionOrNull()!!) }
                            },
                        )
                    val paymentBackend = paymentOwner.get(10, TimeUnit.SECONDS)
                    val b =
                        executor.submit(
                            Callable {
                                runCatching {
                                    fixture.transactor.inTransaction(isolation) { transaction ->
                                        val invoice = fixture.ledger.issueInvoice(transaction, document.id)
                                        lineageOwner.complete(fixture.pid(transaction))
                                        fixture.ledger.allocatePayment(
                                            transaction,
                                            payment.id,
                                            committedAllocation,
                                            invoice.reference,
                                            depositMoney("25"),
                                            Instant.EPOCH,
                                        )
                                    }
                                }.also { if (!lineageOwner.isDone) lineageOwner.completeExceptionally(it.exceptionOrNull()!!) }
                            },
                        )
                    val lineageBackend = lineageOwner.get(10, TimeUnit.SECONDS)
                    val blockedQuery = fixture.awaitBlocked(lineageBackend, paymentBackend)
                    blockedQuery shouldContain "commerce.payment_records"
                    blockedQuery shouldContain "FOR UPDATE"
                    b.isDone shouldBe false
                    attemptLineage.countDown()
                    val conflict = a.get(10, TimeUnit.SECONDS).exceptionOrNull().shouldBeInstanceOf<CommerceFailure.Conflict>()
                    conflict.message!! shouldContain "competing mutation"
                    val states =
                        generateSequence<Throwable>(
                            conflict,
                        ) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }.toList()
                    states.shouldContainExactly("55P03")
                    b.get(10, TimeUnit.SECONDS).getOrThrow().id shouldBe committedAllocation
                    fixture.ledger
                        .history(document.id)
                        .map { it.version }
                        .shouldContainExactly(Version.of(1), Version.of(2))
                    val history = fixture.ledger.paymentHistory(payment.id)
                    history.allocations.map { it.id }.shouldContainExactly(committedAllocation)
                    history.reconciliation.netAllocated shouldBe depositMoney("25")
                } finally {
                    attemptLineage.countDown()
                    executor.shutdownNow()
                }
            }
        }

        test("caller-owned mixed mutations compose in either order and reacquire their own locks") {
            listOf(false, true).forEach { paymentFirst ->
                val document = fixture.document()
                val payment = fixture.payment()
                fixture.transactor.inTransaction { transaction ->
                    if (paymentFirst) {
                        fixture.ledger.allocatePayment(
                            transaction,
                            payment.id,
                            UUID.randomUUID(),
                            document.reference,
                            depositMoney("10"),
                            Instant.EPOCH,
                        )
                    }
                    fixture.ledger.issueInvoice(transaction, document.id)
                    val active =
                        fixture.ledger.activateDepositRequirement(
                            transaction,
                            document.id,
                            Version.of(2),
                            DepositTerms.Fixed(depositMoney("20")),
                            null,
                        )
                    val withdrawn = fixture.ledger.withdrawDepositRequirement(transaction, document.id, active.requirement.revision)
                    fixture.ledger.activateDepositRequirement(
                        transaction,
                        document.id,
                        Version.of(2),
                        DepositTerms.Fixed(depositMoney("30")),
                        withdrawn.requirement.revision,
                    )
                    if (!paymentFirst) {
                        fixture.ledger.allocatePayment(
                            transaction,
                            payment.id,
                            UUID.randomUUID(),
                            document.reference,
                            depositMoney("10"),
                            Instant.EPOCH,
                        )
                    }
                    fixture.ledger.recordRefund(
                        transaction,
                        payment.id,
                        UUID.randomUUID(),
                        depositMoney("5"),
                        PaymentMethod.CASH,
                        Instant.EPOCH,
                    )
                    fixture.ledger.reconcilePayment(transaction, payment.id).netAllocated shouldBe depositMoney("10")
                }
                fixture.ledger.depositRequirementHistory(document.id).size shouldBe 3
                fixture.ledger
                    .paymentHistory(payment.id)
                    .refunds.size shouldBe 1
            }
        }

        test("V13's trigger refuses a busy lineage even when the writer bypasses the repository lock") {
            val document = fixture.document()
            val outcome =
                fixture.contendForLineage(
                    holding = { transaction ->
                        fixture.ledger.activateDepositRequirement(
                            transaction,
                            document.id,
                            document.version,
                            DepositTerms.Fixed(depositMoney("25")),
                            null,
                        )
                    },
                    waiting = { transaction ->
                        transaction.handle
                            .createUpdate(
                                """INSERT INTO commerce.financial_document_snapshots (document_id, version, previous_version, stage, lines)
                           SELECT document_id, 2, 1, 'INVOICE', lines FROM commerce.financial_document_snapshots
                           WHERE document_id = :id AND version = 1""",
                            ).bind("id", document.id)
                            .execute()
                    },
                )
            val failure = outcome.exceptionOrNull().shouldBeInstanceOf<UnableToExecuteStatementException>()
            generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<SQLException>()
                .map { it.sqlState }
                .toList()
                .shouldContainExactly("55P03")
            fixture.ledger.history(document.id).size shouldBe 1
        }

        listOf(false, true).forEach { withdrawal ->
            test(
                "repeatable-read ${if (withdrawal) "withdrawal" else "document successor"} rejects a changed lineage synchronization row",
            ) {
                val document = fixture.document()
                val active =
                    fixture.ledger.activateDepositRequirement(
                        document.id,
                        document.version,
                        DepositTerms.Fixed(depositMoney("25")),
                        null,
                    )
                val conflict =
                    shouldThrow<CommerceFailure.Conflict> {
                        fixture.transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
                            fixture.ledger.latest(transaction, document.id) shouldBe document
                            fixture.onOtherThread { fixture.ledger.issueInvoice(document.id) }
                            if (withdrawal) {
                                fixture.ledger.withdrawDepositRequirement(transaction, document.id, active.requirement.revision)
                            } else {
                                fixture.ledger.issueInvoice(transaction, document.id)
                            }
                        }
                    }
                generateSequence<Throwable>(conflict) { it.cause }
                    .filterIsInstance<SQLException>()
                    .map { it.sqlState }
                    .toList()
                    .shouldContainExactly("40001")
                fixture.ledger.history(document.id).size shouldBe 2
                fixture.ledger.depositRequirementHistory(document.id).size shouldBe 1
            }
        }
    })
