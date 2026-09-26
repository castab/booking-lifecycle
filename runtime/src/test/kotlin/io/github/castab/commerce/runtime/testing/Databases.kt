package io.github.castab.commerce.runtime.testing

import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.runtime.persistence.createDataSource
import javax.sql.DataSource

/** Runs [block] against a fresh [TestDatabase] and its own pool, then drops the database. */
inline fun <T> withTestDatabase(block: (TestDatabase, HikariDataSource) -> T): T {
    val database = TestDatabase.create()
    try {
        return createDataSource(database.configuration, poolName = "test-database").use { block(database, it) }
    } finally {
        database.close()
    }
}

/** The versions successfully applied in [schema]'s Flyway history, in order; empty when it has none. */
fun DataSource.appliedVersions(schema: String): List<String> =
    if (!relationExists("$schema.flyway_schema_history")) {
        emptyList()
    } else {
        strings(
            "SELECT version FROM $schema.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
        )
    }

/** Every row of [schema]'s Flyway history, for comparing a history before and after an operation. */
fun DataSource.history(schema: String): List<String> =
    strings("SELECT concat_ws('|', installed_rank, version, checksum, installed_on, success) FROM $schema.flyway_schema_history")

fun DataSource.relationExists(name: String): Boolean = strings("SELECT (to_regclass('$name') IS NOT NULL)::text").single() == "true"

fun DataSource.schemaExists(name: String): Boolean = strings("SELECT (to_regnamespace('$name') IS NOT NULL)::text").single() == "true"

fun DataSource.execute(sql: String) {
    connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
}

private fun DataSource.strings(sql: String): List<String> =
    connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }
