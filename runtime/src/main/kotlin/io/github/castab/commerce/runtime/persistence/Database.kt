package io.github.castab.commerce.runtime.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Creates the runtime's single HikariCP connection pool. The caller owns and closes it.
 *
 * Every pooled connection is `READ COMMITTED`, the PostgreSQL default made explicit. Hikari
 * also restores that level when a connection whose level changed is returned, so a
 * transaction that requested another [TransactionIsolation] cannot leak it to the next
 * borrower.
 */
fun createDataSource(
    configuration: CommerceRuntimeConfiguration.Database,
    poolName: String = "commerce-runtime",
): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = configuration.jdbcUrl
            username = configuration.username
            password = configuration.password
            maximumPoolSize = configuration.maximumPoolSize
            minimumIdle = configuration.minimumIdle
            connectionTimeout = configuration.connectionTimeoutMs
            validationTimeout = configuration.validationTimeoutMs
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            this.poolName = poolName
        },
    )

/** True when a pooled connection can be obtained and validated. Used by readiness checks. */
fun DataSource.isReachable(timeoutSeconds: Int = 1): Boolean =
    try {
        connection.use { it.isValid(timeoutSeconds) }
    } catch (_: SQLException) {
        false
    }

private const val UNIQUE_VIOLATION = "23505"

/**
 * True when this failure, or one of its causes, is a PostgreSQL unique-constraint
 * violation. Repositories translate it into a conflict failure instead of letting
 * database detail reach callers.
 */
fun Throwable.isUniqueViolation(): Boolean =
    generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .any { it.sqlState == UNIQUE_VIOLATION }

/** A stale PostgreSQL transaction snapshot must be retried by the caller as a whole. */
internal fun Throwable.isSerializationFailure(): Boolean =
    generateSequence(this) { it.cause }.filterIsInstance<SQLException>().any { it.sqlState == "40001" }
