package io.github.castab.commerce.runtime.persistence

import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.transaction.TransactionIsolationLevel

/**
 * One open PostgreSQL transaction.
 *
 * Only a [Transactor] creates one. Repositories take it as an explicit parameter and
 * never begin, commit, or roll back on their own, so every write an application
 * operation makes through the same [Transaction] commits or rolls back together.
 */
class Transaction internal constructor(
    /** The JDBI handle bound to this transaction, for repository implementations. */
    val handle: Handle,
)

/**
 * The isolation level an operation explicitly requests for one PostgreSQL transaction.
 *
 * The runtime intentionally supports only the levels its consumers need. A requested level
 * applies to the whole transaction, is in effect before the transaction's first statement,
 * and is scoped to that one [Transactor.inTransaction] call: it never carries over to
 * another transaction, even one that reuses the same pooled connection.
 */
enum class TransactionIsolation {
    /**
     * PostgreSQL `READ COMMITTED`. Every statement sees the data committed before that
     * statement began, so two reads in one transaction can disagree when another
     * transaction commits between them.
     */
    READ_COMMITTED,

    /**
     * PostgreSQL `REPEATABLE READ`. The first statement establishes one snapshot and every
     * later read in the transaction observes it, however many other transactions commit in
     * the meantime. Readers do not block writers.
     *
     * It gives coherent multi-query reads. It does not serialize writes: an application that
     * needs that keeps using explicit row locking or expected-version checks.
     */
    REPEATABLE_READ,
}

/**
 * The explicit transaction boundary of operations.
 *
 * [inTransaction] commits when [block] returns and rolls back when it throws, rethrowing
 * the original exception. An operation that coordinates several persistent concepts, for
 * example recording a payment, its allocation, and a resulting booking transition, does
 * all of it inside one [inTransaction] call.
 *
 * **Compose through the caller-owned [Transaction].** An operation opens one transaction,
 * chooses its isolation there, and passes the [Transaction] to repositories and services.
 * They all participate in that one transaction and inherit its isolation; none takes an
 * isolation argument:
 *
 * ```
 * transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
 *     repositoryA.find(transaction, ...)
 *     repositoryB.find(transaction, ...) // the same snapshot
 * }
 * ```
 *
 * **Isolation.** [inTransaction] without an isolation uses the runtime's default: the pool
 * baseline, which [createDataSource] sets to `READ COMMITTED`. Passing a
 * [TransactionIsolation] requests that level explicitly, whatever the connection's baseline.
 * Choose it at the outer transaction boundary.
 *
 * **Nesting.** Calling [inTransaction] again on the same thread from inside [block] does not
 * open a second transaction: JDBI joins the managed handle, so the inner block runs on the
 * same connection and commits or rolls back with the outer one. An inner call without an
 * isolation inherits the outer transaction's, and one that repeats it is accepted. An inner
 * call that requests a different level is rejected with JDBI's `TransactionException`,
 * because the level of an open transaction cannot change; if that exception escapes the
 * outer block, the outer transaction rolls back. Prefer passing the [Transaction] down over
 * nesting, and choose the isolation once, at the outer boundary.
 */
class Transactor(
    private val jdbi: Jdbi,
) {
    /** Runs [block] in one transaction at the runtime's default isolation. */
    fun <T> inTransaction(block: (Transaction) -> T): T =
        jdbi.inTransaction<T, RuntimeException> { handle ->
            block(Transaction(handle))
        }

    /**
     * Runs [block] in one transaction at exactly [isolation]. The level is applied to the
     * connection before the transaction begins and restored when it ends, before the
     * connection returns to the pool.
     */
    fun <T> inTransaction(
        isolation: TransactionIsolation,
        block: (Transaction) -> T,
    ): T =
        jdbi.inTransaction<T, RuntimeException>(isolation.toJdbi()) { handle ->
            block(Transaction(handle))
        }
}

private fun TransactionIsolation.toJdbi(): TransactionIsolationLevel =
    when (this) {
        TransactionIsolation.READ_COMMITTED -> TransactionIsolationLevel.READ_COMMITTED
        TransactionIsolation.REPEATABLE_READ -> TransactionIsolationLevel.REPEATABLE_READ
    }
