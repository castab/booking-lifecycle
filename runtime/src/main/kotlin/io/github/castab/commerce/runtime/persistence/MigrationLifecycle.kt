package io.github.castab.commerce.runtime.persistence

import io.github.oshai.kotlinlogging.KotlinLogging
import org.flywaydb.core.Flyway
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

/**
 * The migration phase of the commerce runtime: the runtime's own migrations, then the
 * application's.
 *
 * Each participant owns its migrations, and the runtime owns their orchestration. The two
 * streams are versioned independently, each with its own schema history, so their version
 * numbers never compete:
 *
 * | Stream | Owner | Discovered at | Schema history |
 * |---|---|---|---|
 * | runtime migrations | commerce-runtime | `classpath:db/commerce`, internally | `commerce.flyway_schema_history` |
 * | application migrations | the concrete application | [ApplicationMigrations.locations] | `<schema>.flyway_schema_history`, in the application's own [ApplicationMigrations.schema] |
 *
 * A schema that owns data also owns the Flyway history describing it, so application
 * migration metadata never lives in `public`: the application declares its own schema, and the
 * runtime creates it, when missing, before recording its history there. An installation that
 * predates this rule and holds its application history in `public.flyway_schema_history` is
 * not migrated or reinterpreted; see [ApplicationMigrations].
 *
 * [migrate] applies the runtime migrations and only then the application migrations, so an
 * application migration may reference runtime-owned objects created by the runtime
 * migrations of the same run. Flyway validates each stream before migrating it: an edited or
 * missing migration fails the stream before it applies anything. When the runtime stream
 * fails, the application stream is not attempted. Failures propagate as Flyway's own
 * exceptions, which name the migration script and carry the database error.
 *
 * `commerceRuntime(...)` runs this phase before composing anything else. A separate
 * migration step, such as a release job that runs before instances are deployed, uses this
 * class directly and never starts the HTTP server:
 *
 * ```kotlin
 * createDataSource(configuration.database).use { dataSource ->
 *     MigrationLifecycle(dataSource, application.migrations).migrate()
 * }
 * ```
 *
 * Several instances may run this phase against the same database at once. Flyway
 * serializes each stream with a PostgreSQL advisory lock keyed by its schema history table:
 * one instance applies the pending migrations while the others wait, then find nothing
 * pending. Waiting is bounded by Flyway's lock retry limit (50 attempts one second apart by
 * default), after which the waiting instance fails to start.
 *
 * Ownership is an architectural contract, not SQL analysis: runtime migrations create,
 * alter, and drop only runtime-owned objects in the `commerce` schema, and never
 * application-owned objects. Application migrations never alter runtime-owned objects.
 */
class MigrationLifecycle internal constructor(
    private val runtime: RuntimeMigrations,
    private val application: ApplicationMigrationStream?,
) {
    /** [application] describes the application's own migrations only; `null` when it has none. */
    constructor(
        dataSource: DataSource,
        application: ApplicationMigrations? = null,
    ) : this(RuntimeMigrations(dataSource), application?.let { ApplicationMigrationStream(dataSource, it) })

    /** Applies the pending runtime migrations, then the pending application migrations. */
    fun migrate() {
        runtime.migrate()
        application?.migrate()
    }

    /**
     * Fails unless both streams are fully applied and unchanged; applies nothing. Migrations
     * newer than this runtime or application, applied by a later release, are accepted.
     */
    @JvmSynthetic
    internal fun validate() {
        runtime.validate()
        application?.validate()
    }
}

/**
 * One participant's Flyway migration stream: its locations, the schema it owns, and its own
 * `flyway_schema_history` table in that schema. Flyway creates the schema when it is missing,
 * before it records anything there. A stream without locations does nothing.
 */
internal sealed class MigrationStream(
    private val owner: String,
    dataSource: DataSource,
    private val schema: String,
    locations: List<String>,
) {
    // Flyway's defaults are deliberately kept: validation before migrating, no out-of-order
    // or baseline-on-migrate, clean disabled, and PostgreSQL advisory locking.
    private val flyway: Flyway? =
        if (locations.isEmpty()) {
            null
        } else {
            Flyway
                .configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(true)
                .table(HISTORY_TABLE)
                .locations(*locations.toTypedArray())
                .failOnMissingLocations(true)
                .load()
        }

    fun migrate() {
        val flyway = flyway ?: return
        logger.info { "event=migrations_started owner=$owner schema=$schema" }
        val result =
            try {
                flyway.migrate()
            } catch (e: RuntimeException) {
                logger.error { "event=migrations_failed owner=$owner schema=$schema" }
                throw e
            }
        logger.info {
            "event=migrations_completed owner=$owner schema=$schema applied=${result.migrationsExecuted} " +
                "version=${result.targetSchemaVersion ?: result.initialSchemaVersion}"
        }
    }

    fun validate() {
        val flyway = flyway ?: return
        try {
            flyway.validate()
        } catch (e: RuntimeException) {
            logger.error { "event=migrations_invalid owner=$owner schema=$schema" }
            throw e
        }
        logger.info { "event=migrations_validated owner=$owner schema=$schema" }
    }

    companion object {
        const val HISTORY_TABLE = "flyway_schema_history"
    }
}

/**
 * commerce-runtime's own migrations. Their location is internal: applications never list it.
 * Only the runtime's own tests pass another [location], to stand in for runtime migrations.
 */
internal class RuntimeMigrations(
    dataSource: DataSource,
    location: String = LOCATION,
) : MigrationStream("runtime", dataSource, SCHEMA, listOf(location)) {
    companion object {
        /** The PostgreSQL schema that holds every runtime-owned database object. */
        const val SCHEMA = "commerce"

        /** Where the runtime's own migrations are discovered, inside the commerce-runtime jar. */
        const val LOCATION = "classpath:db/commerce"
    }
}

/**
 * The concrete application's migration stream: the locations it contributes, run in the
 * schema it declared, with the history in that schema. [ApplicationMigrations] validates the
 * schema and the locations.
 */
internal class ApplicationMigrationStream(
    dataSource: DataSource,
    migrations: ApplicationMigrations,
) : MigrationStream("application", dataSource, migrations.schema, migrations.locations)
