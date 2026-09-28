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
 * The isolation level of one PostgreSQL transaction, chosen by the operation that opens it.
 *
 * The runtime intentionally supports only the levels its consumers need. The level applies
 * to the whole transaction, is in effect before the transaction's first statement, and is
 * scoped to that one [Transactor.inTransaction] call: it never carries over to another
 * transaction, even one that reuses the same pooled connection.
 */
enum class TransactionIsolation {
    /**
     * PostgreSQL `READ COMMITTED`, the default. Every statement sees the data committed
     * before that statement began, so two reads in one transaction can disagree when
     * another transaction commits between them.
     */
    READ_COMMITTED,

    /**
     * PostgreSQL `REPEATABLE READ`. The first statement establishes one snapshot and every
     * later read in the transaction observes it, however many other transactions commit in
     * the meantime. Readers do not block writers. Must be requested explicitly.
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
 * all of it inside one [inTransaction] call. Calling [inTransaction] again from inside
 * [block] opens a separate transaction; pass the existing [Transaction] instead.
 *
 * The [TransactionIsolation] belongs to the transaction opened here. Repositories and
 * operations that receive the [Transaction] inherit it and never choose their own.
 *
 * ```
 * val result =
 *     transactor.inTransaction(isolation = TransactionIsolation.REPEATABLE_READ) { transaction ->
 *         // Every SELECT in this block observes one PostgreSQL snapshot.
 *     }
 * ```
 */
class Transactor(
    private val jdbi: Jdbi,
) {
    /**
     * Runs [block] in one transaction at [isolation], which defaults to
     * [TransactionIsolation.READ_COMMITTED].
     *
     * A non-default level is applied to the connection before the transaction begins and
     * restored when it ends, before the connection returns to the pool. The default runs on
     * the pool's baseline, which [createDataSource] establishes as `READ COMMITTED`, so an
     * ordinary transaction pays nothing extra.
     */
    @JvmOverloads
    fun <T> inTransaction(
        isolation: TransactionIsolation = TransactionIsolation.READ_COMMITTED,
        block: (Transaction) -> T,
    ): T =
        when (isolation) {
            TransactionIsolation.READ_COMMITTED ->
                jdbi.inTransaction<T, RuntimeException> { handle -> block(Transaction(handle)) }
            TransactionIsolation.REPEATABLE_READ ->
                jdbi.inTransaction<T, RuntimeException>(TransactionIsolationLevel.REPEATABLE_READ) { handle ->
                    block(Transaction(handle))
                }
        }
}
