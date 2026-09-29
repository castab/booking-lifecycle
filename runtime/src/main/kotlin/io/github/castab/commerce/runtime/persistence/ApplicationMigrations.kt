package io.github.castab.commerce.runtime.persistence

/**
 * The migrations of the one concrete application a runtime instance serves: the PostgreSQL
 * [schema] the application owns, and the Flyway [locations] of its migration scripts.
 *
 * A schema that owns application data also owns the Flyway history that describes it.
 * Application migrations run with [schema] as Flyway's default and only managed schema, so
 * the application's `flyway_schema_history` table lives in `<schema>.flyway_schema_history`,
 * beside the application's tables, and never in `public`. The runtime's own migrations remain
 * a separate stream in the `commerce` schema, with `commerce.flyway_schema_history`.
 *
 * The runtime creates [schema] when it does not exist yet, before Flyway records anything in
 * it, so a clean database needs no preparation. It never hard-codes an application schema
 * name: the application declares it here.
 *
 * Because [schema] is the only schema on the search path while an application migration
 * runs, an unqualified `CREATE TABLE foo (...)` lands in [schema], not in `public`. Prefer
 * qualifying names explicitly anyway, and always qualify references to runtime-owned
 * objects (`commerce.<table>`).
 *
 * The schema must be a lower-case, unquoted PostgreSQL identifier (`[a-z][a-z0-9_]*`). It
 * cannot be `commerce` (owned by the runtime), `public` (shared, and the schema this rule
 * exists to keep application metadata out of), `information_schema`, or a `pg_` schema.
 *
 * A [location] that would also discover the runtime's migrations (`db/commerce`, an
 * ancestor such as `db`, or a descendant) is rejected, because it would apply runtime-owned
 * migrations under the application's history.
 *
 * An application without migrations of its own contributes none (`null`), and then has no
 * schema or history managed by the runtime.
 *
 * **Installations that predate this rule.** Earlier runtime versions kept the application's
 * history in `public.flyway_schema_history`. The runtime does not copy, rename, baseline, or
 * reinterpret that history, so [schema] starts with none. Where the application's tables
 * already sit in [schema], Flyway refuses to migrate or validate a non-empty schema that has
 * no schema history (the runtime never enables `baselineOnMigrate`), and startup fails
 * instead of running migrations again. Recreate such a database, or move its history
 * deliberately as a one-time operation owned by the application. An empty [schema],
 * including one created in advance, is used as it is.
 */
class ApplicationMigrations(
    val schema: String,
    locations: List<String>,
) {
    val locations: List<String> = locations.toList()

    init {
        require(SCHEMA_IDENTIFIER.matches(schema)) {
            "Application migration schema \"$schema\" must be a lower-case PostgreSQL identifier " +
                "of at most $MAX_SCHEMA_LENGTH characters ([a-z][a-z0-9_]*)"
        }
        require(schema != RuntimeMigrations.SCHEMA) {
            "Application migration schema must not be \"${RuntimeMigrations.SCHEMA}\", which is owned by commerce-runtime"
        }
        require(schema !in RESERVED_SCHEMAS && !schema.startsWith("pg_")) {
            "Application migration schema \"$schema\" is a shared or system schema; the application must own its own schema " +
                "so that its migration history does not live in a shared one"
        }
        require(this.locations.isNotEmpty()) {
            "Application migrations for schema \"$schema\" need at least one location; contribute none (null) otherwise"
        }
        this.locations.forEach { location ->
            require(!location.overlapsRuntimeLocation()) {
                "Application migration location $location overlaps commerce-runtime's own migrations " +
                    "(${RuntimeMigrations.LOCATION}); application migrations must live in their own location"
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is ApplicationMigrations && schema == other.schema && locations == other.locations

    override fun hashCode(): Int = 31 * schema.hashCode() + locations.hashCode()

    override fun toString(): String = "ApplicationMigrations(schema=$schema, locations=$locations)"

    private companion object {
        const val MAX_SCHEMA_LENGTH = 63
        val SCHEMA_IDENTIFIER = Regex("[a-z][a-z0-9_]{0,${MAX_SCHEMA_LENGTH - 1}}")
        val RESERVED_SCHEMAS = setOf("public", "information_schema")
    }
}

private fun String.overlapsRuntimeLocation(): Boolean {
    // Flyway treats a location without a prefix as a classpath location.
    if (contains(':') && !startsWith("classpath:")) return false
    val path = removePrefix("classpath:").trim('/')
    val runtimePath = RuntimeMigrations.LOCATION.removePrefix("classpath:")
    return path.isEmpty() || path == runtimePath || runtimePath.startsWith("$path/") || path.startsWith("$runtimePath/")
}
