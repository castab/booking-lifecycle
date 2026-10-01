package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.runtime.persistence.MigrationStream.Companion.HISTORY_TABLE
import io.github.castab.commerce.runtime.testing.TEST_APPLICATION_SCHEMA
import io.github.castab.commerce.runtime.testing.appliedVersions
import io.github.castab.commerce.runtime.testing.execute
import io.github.castab.commerce.runtime.testing.history
import io.github.castab.commerce.runtime.testing.relationExists
import io.github.castab.commerce.runtime.testing.schemaExists
import io.github.castab.commerce.runtime.testing.strings
import io.github.castab.commerce.runtime.testing.testApplicationMigrations
import io.github.castab.commerce.runtime.testing.withTestDatabase
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The migration phase against a real PostgreSQL: the runtime stream is discovered
 * internally, the application stream comes from contributed locations in the application's
 * own schema, the runtime stream always runs first, and the two keep independent histories and
 * version spaces, each in the schema of its owner. Every test uses a fresh database.
 *
 * Dependency-order tests use a small stand-in runtime stream from `db/testruntime`.
 */
class MigrationLifecycleSpec :
    FunSpec({
        val testApplication = testApplicationMigrations()
        val dependentApplication = testApplicationMigrations("classpath:db/testapp-dependent")
        val applicationHistory = "$TEST_APPLICATION_SCHEMA.$HISTORY_TABLE"

        // The lifecycle with the stand-in runtime stream (V1 creates commerce.test_runtime_records).
        fun standInLifecycle(
            dataSource: DataSource,
            application: ApplicationMigrations,
        ) = MigrationLifecycle(
            RuntimeMigrations(dataSource, location = "classpath:db/testruntime"),
            ApplicationMigrationStream(dataSource, application),
        )

        fun migrateRuntimeThrough(
            dataSource: DataSource,
            version: String,
        ) {
            Flyway
                .configure()
                .dataSource(dataSource)
                .schemas(RuntimeMigrations.SCHEMA)
                .createSchemas(true)
                .table(HISTORY_TABLE)
                .locations(RuntimeMigrations.LOCATION)
                .target(version)
                .load()
                .migrate()
        }

        test("runtime migrations are discovered internally and own the commerce schema and its history") {
            withTestDatabase { _, dataSource ->
                MigrationLifecycle(dataSource).migrate()

                dataSource.appliedVersions(RuntimeMigrations.SCHEMA).shouldNotBeEmpty()
                // No application stream: no application schema or history is created.
                dataSource.relationExists("public.$HISTORY_TABLE") shouldBe false
                dataSource.schemaExists(TEST_APPLICATION_SCHEMA) shouldBe false
            }
        }

        test("an application migration that depends on a runtime-owned table fails on its own") {
            withTestDatabase { _, dataSource ->
                shouldThrow<FlywayException> {
                    ApplicationMigrationStream(dataSource, dependentApplication).migrate()
                }.message shouldContain "schema \"commerce\" does not exist"

                dataSource.relationExists("$TEST_APPLICATION_SCHEMA.test_application_dependents") shouldBe false
            }
        }

        test("the same application migration succeeds because the runtime stream runs first") {
            withTestDatabase { _, dataSource ->
                standInLifecycle(dataSource, dependentApplication).migrate()

                dataSource.relationExists("commerce.test_runtime_records") shouldBe true
                dataSource.relationExists("$TEST_APPLICATION_SCHEMA.test_application_dependents") shouldBe true
                dataSource.relationExists("public.$HISTORY_TABLE") shouldBe false
            }
        }

        test("the two streams have independent version spaces, so equal versions do not collide") {
            withTestDatabase { _, dataSource ->
                MigrationLifecycle(dataSource, testApplication).migrate()

                // Runtime V1 to V9 coexist with application V1 in separate version spaces.
                dataSource.appliedVersions(RuntimeMigrations.SCHEMA) shouldContainExactly
                    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
                dataSource.appliedVersions(TEST_APPLICATION_SCHEMA) shouldContainExactly listOf("1")
            }
        }

        test("canonical runtime migrations V1 through V7 are unchanged") {
            withTestDatabase { _, dataSource ->
                RuntimeMigrations(dataSource).migrate()

                // V1-V6 are released and V7 is canonical for the next release. A change to
                // any of these scripts needs a new migration, not an edited checksum.
                val canonical =
                    mapOf(
                        "1" to "-1133615564",
                        "2" to "839788254",
                        "3" to "80008543",
                        "4" to "1009054310",
                        "5" to "-265215424",
                        "6" to "2123768785",
                        "7" to "1335984745",
                    )
                dataSource
                    .strings(
                        "SELECT version || '=' || checksum FROM commerce.$HISTORY_TABLE WHERE version::int <= 7 ORDER BY installed_rank",
                    ).shouldContainExactly(canonical.map { (version, checksum) -> "$version=$checksum" })
            }
        }

        test("a database at the released V5 ledger migrates through refunds and timestamps, keeping its payment facts") {
            withTestDatabase { _, dataSource ->
                migrateRuntimeThrough(dataSource, "5")
                dataSource.appliedVersions(RuntimeMigrations.SCHEMA) shouldContainExactly listOf("1", "2", "3", "4", "5")
                dataSource.relationExists("commerce.refund_records") shouldBe false
                dataSource.execute(
                    "INSERT INTO commerce.payment_records (payment_id, amount, currency, method, received_at_seconds, received_at_nanos) " +
                        "VALUES ('00000000-0000-0000-0000-000000000005', 500.00, 'USD', 'CASH', 0, 0)",
                )

                MigrationLifecycle(dataSource, testApplication).migrate()

                dataSource.appliedVersions(RuntimeMigrations.SCHEMA) shouldContainExactly
                    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
                dataSource.appliedVersions(TEST_APPLICATION_SCHEMA) shouldContainExactly listOf("1")
                dataSource.strings("SELECT amount::text FROM commerce.payment_records") shouldContainExactly listOf("500.00")
                dataSource.relationExists("commerce.refund_records") shouldBe true
                dataSource.relationExists("commerce.refund_allocations") shouldBe true

                val history = dataSource.history(RuntimeMigrations.SCHEMA)
                MigrationLifecycle(dataSource, testApplication).migrate()
                dataSource.history(RuntimeMigrations.SCHEMA) shouldBe history
            }
        }

        test("an empty V6 ledger migrates to V7 with a non-null PostgreSQL default for new snapshots") {
            withTestDatabase { _, dataSource ->
                migrateRuntimeThrough(dataSource, "6")
                dataSource.appliedVersions(RuntimeMigrations.SCHEMA) shouldContainExactly listOf("1", "2", "3", "4", "5", "6")
                RuntimeMigrations(dataSource).migrate()
                dataSource.appliedVersions(RuntimeMigrations.SCHEMA) shouldContainExactly
                    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
                dataSource.strings(
                    """SELECT is_nullable || ':' || column_default
                       FROM information_schema.columns
                       WHERE table_schema = 'commerce' AND table_name = 'financial_document_snapshots'
                         AND column_name = 'created_at'""",
                ) shouldContainExactly listOf("NO:clock_timestamp()")
                dataSource.execute(
                    """INSERT INTO commerce.financial_document_snapshots (document_id, version, stage, lines)
                       VALUES ('00000000-0000-0000-0000-000000000007', 1, 'ESTIMATE', '[]')""",
                )
                dataSource.strings(
                    """SELECT (created_at IS NOT NULL)::text FROM commerce.financial_document_snapshots
                       WHERE document_id = '00000000-0000-0000-0000-000000000007'""",
                ) shouldContainExactly listOf("true")
            }
        }

        test("V7 rejects preexisting snapshots without adding a column or changing their facts") {
            withTestDatabase { _, dataSource ->
                migrateRuntimeThrough(dataSource, "6")
                dataSource.execute(
                    """INSERT INTO commerce.financial_document_snapshots (document_id, version, stage)
                       VALUES ('00000000-0000-0000-0000-000000000006', 1, 'ESTIMATE')""",
                )
                shouldThrow<FlywayException> { RuntimeMigrations(dataSource).migrate() }
                    .message shouldContain "V7 cannot timestamp preexisting financial document snapshots"
                dataSource.appliedVersions(RuntimeMigrations.SCHEMA) shouldContainExactly listOf("1", "2", "3", "4", "5", "6")
                dataSource.strings(
                    """SELECT document_id::text || ':' || version || ':' || stage
                       FROM commerce.financial_document_snapshots""",
                ) shouldContainExactly listOf("00000000-0000-0000-0000-000000000006:1:ESTIMATE")
                dataSource.strings(
                    """SELECT count(*)::text FROM information_schema.columns
                       WHERE table_schema = 'commerce' AND table_name = 'financial_document_snapshots'
                         AND column_name = 'created_at'""",
                ) shouldContainExactly listOf("0")
            }
        }

        test("V8 adds required selection columns without defaults and refuses to invent historical state") {
            withTestDatabase { _, dataSource ->
                migrateRuntimeThrough(dataSource, "8")
                dataSource
                    .strings(
                        """SELECT column_name || ':' || is_nullable || ':' || coalesce(column_default, 'NONE')
                       FROM information_schema.columns WHERE table_schema = 'commerce' AND table_name = 'offerings'
                         AND column_name IN ('selection_state', 'availability') ORDER BY column_name""",
                    ).shouldContainExactly("availability:NO:NONE", "selection_state:NO:NONE")
            }
            withTestDatabase { _, dataSource ->
                migrateRuntimeThrough(dataSource, "7")
                val id = "00000000-0000-0000-0000-000000000008"
                dataSource.execute("INSERT INTO commerce.offerings_snapshots (catalog_id, revision) VALUES ('$id', 1)")
                dataSource.execute(
                    """INSERT INTO commerce.offering_categories (catalog_id, revision, category_key, position, display_name, minimum_selections)
                       VALUES ('$id', 1, 'choice', 0, 'Choice', 0)""",
                )
                dataSource.execute(
                    """INSERT INTO commerce.offerings (catalog_id, revision, offering_key, category_key, position, display_name)
                       VALUES ('$id', 1, 'item', 'choice', 0, 'Item')""",
                )
                shouldThrow<FlywayException> { RuntimeMigrations(dataSource).migrate() }
                    .message shouldContain "V8 cannot assign selection and availability to preexisting offerings"
                dataSource.appliedVersions(RuntimeMigrations.SCHEMA).shouldContainExactly("1", "2", "3", "4", "5", "6", "7")
                dataSource.strings("SELECT offering_key FROM commerce.offerings").shouldContainExactly("item")
                dataSource
                    .strings(
                        """SELECT count(*)::text FROM information_schema.columns
                       WHERE table_schema = 'commerce' AND table_name = 'offerings'
                         AND column_name IN ('selection_state', 'availability')""",
                    ).shouldContainExactly("0")
            }
        }

        test("V9 stores aggregate-owned values in their snapshot rows and drops the child tables") {
            withTestDatabase { _, dataSource ->
                RuntimeMigrations(dataSource).migrate()

                listOf("financial_document_lines", "offerings", "offering_categories").forEach {
                    dataSource.relationExists("commerce.$it") shouldBe false
                }
                // The parent snapshots stay relational, and their identity and the payment reference to it remain.
                dataSource
                    .strings(
                        """SELECT table_name || '.' || column_name || ':' || data_type || ':' || is_nullable || ':' ||
                                  coalesce(column_default, 'NONE')
                           FROM information_schema.columns
                           WHERE table_schema = 'commerce'
                             AND ((table_name = 'financial_document_snapshots' AND column_name = 'lines') OR
                                  (table_name = 'offerings_snapshots' AND column_name = 'catalog'))
                           ORDER BY table_name""",
                    ).shouldContainExactly(
                        "financial_document_snapshots.lines:jsonb:NO:NONE",
                        "offerings_snapshots.catalog:jsonb:NO:NONE",
                    )
                dataSource
                    .strings(
                        """SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                           FROM pg_constraint c
                           CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
                           JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                           WHERE c.contype = 'p' AND c.conrelid IN
                               ('commerce.financial_document_snapshots'::regclass, 'commerce.offerings_snapshots'::regclass)
                           GROUP BY c.conrelid ORDER BY c.conrelid::regclass::text""",
                    ).shouldContainExactly("document_id,version", "catalog_id,revision")
                dataSource
                    .strings(
                        """SELECT f.relname FROM pg_constraint c JOIN pg_class f ON f.oid = c.confrelid
                           WHERE c.contype = 'f' AND c.conrelid = 'commerce.payment_allocations'::regclass AND f.relname LIKE '%snapshots'""",
                    ).shouldContainExactly("financial_document_snapshots")

                // The stored shapes are checked structurally; their element contents belong to the repositories.
                val id = "00000000-0000-0000-0000-000000000009"
                listOf("'{}'", "'null'", "'\"x\"'").forEach { lines ->
                    shouldThrow<java.sql.SQLException> {
                        dataSource.execute(
                            "INSERT INTO commerce.financial_document_snapshots (document_id, version, stage, lines) VALUES ('$id', 1, 'ESTIMATE', $lines)",
                        )
                    }
                }
                shouldThrow<java.sql.SQLException> {
                    dataSource.execute(
                        "INSERT INTO commerce.financial_document_snapshots (document_id, version, stage) VALUES ('$id', 1, 'ESTIMATE')",
                    )
                }
                dataSource.execute(
                    "INSERT INTO commerce.financial_document_snapshots (document_id, version, stage, lines) VALUES ('$id', 1, 'ESTIMATE', '[]')",
                )
                dataSource.strings("SELECT jsonb_typeof(lines) FROM commerce.financial_document_snapshots").shouldContainExactly("array")
            }
        }

        test("V9 refuses preexisting financial document snapshots and leaves their lines intact") {
            withTestDatabase { _, dataSource ->
                migrateRuntimeThrough(dataSource, "8")
                val id = "00000000-0000-0000-0000-00000000000a"
                dataSource.execute(
                    "INSERT INTO commerce.financial_document_snapshots (document_id, version, stage) VALUES ('$id', 1, 'ESTIMATE')",
                )
                dataSource.execute(
                    """INSERT INTO commerce.financial_document_lines
                       (document_id, version, position, line_id, description, price_amount, price_currency, tax_amount)
                       VALUES ('$id', 1, 0, '$id', 'Line', 1.00, 'USD', 0)""",
                )
                shouldThrow<FlywayException> { RuntimeMigrations(dataSource).migrate() }
                    .message shouldContain "V9 cannot move preexisting financial document lines"
                dataSource.appliedVersions(RuntimeMigrations.SCHEMA).shouldContainExactly("1", "2", "3", "4", "5", "6", "7", "8")
                dataSource.strings("SELECT description FROM commerce.financial_document_lines").shouldContainExactly("Line")
                dataSource.relationExists("commerce.offerings") shouldBe true
            }
        }

        test("V9 refuses preexisting offerings catalog revisions and leaves their rows intact") {
            withTestDatabase { _, dataSource ->
                migrateRuntimeThrough(dataSource, "8")
                val id = "00000000-0000-0000-0000-00000000000b"
                dataSource.execute("INSERT INTO commerce.offerings_snapshots (catalog_id, revision) VALUES ('$id', 1)")
                dataSource.execute(
                    """INSERT INTO commerce.offering_categories (catalog_id, revision, category_key, position, display_name, minimum_selections)
                       VALUES ('$id', 1, 'choice', 0, 'Choice', 0)""",
                )
                dataSource.execute(
                    """INSERT INTO commerce.offerings
                       (catalog_id, revision, offering_key, category_key, position, display_name, selection_state, availability)
                       VALUES ('$id', 1, 'item', 'choice', 0, 'Item', 'ENABLED', 'AVAILABLE')""",
                )
                shouldThrow<FlywayException> { RuntimeMigrations(dataSource).migrate() }
                    .message shouldContain "V9 cannot move preexisting offerings catalog contents"
                dataSource.appliedVersions(RuntimeMigrations.SCHEMA).shouldContainExactly("1", "2", "3", "4", "5", "6", "7", "8")
                dataSource.strings("SELECT offering_key FROM commerce.offerings").shouldContainExactly("item")
                dataSource.relationExists("commerce.financial_document_lines") shouldBe true
            }
        }

        test("an empty V8 database migrates through V9 with the new columns in place") {
            withTestDatabase { _, dataSource ->
                migrateRuntimeThrough(dataSource, "8")
                RuntimeMigrations(dataSource).migrate()
                dataSource.appliedVersions(RuntimeMigrations.SCHEMA).last() shouldBe "9"
                dataSource.relationExists("commerce.offerings") shouldBe false
            }
        }

        test("refund tables belong to the commerce schema and reference the ledger's own tables") {
            withTestDatabase { _, dataSource ->
                MigrationLifecycle(dataSource, testApplication).migrate()

                dataSource.strings(
                    "SELECT schemaname || '.' || tablename FROM pg_tables WHERE tablename LIKE 'refund%' ORDER BY tablename",
                ) shouldContainExactly listOf("commerce.refund_allocations", "commerce.refund_records")
                dataSource.strings(
                    """SELECT cn.nspname || '.' || c.relname || ' -> ' || fn.nspname || '.' || f.relname
                       FROM pg_constraint k
                       JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace cn ON cn.oid = c.relnamespace
                       JOIN pg_class f ON f.oid = k.confrelid JOIN pg_namespace fn ON fn.oid = f.relnamespace
                       WHERE k.contype = 'f' AND c.relname IN ('refund_records', 'refund_allocations')
                       ORDER BY 1""",
                ) shouldContainExactly
                    listOf(
                        "commerce.refund_allocations -> commerce.payment_allocations",
                        "commerce.refund_allocations -> commerce.refund_records",
                        "commerce.refund_records -> commerce.payment_records",
                    )
            }
        }

        test("migrating an up-to-date database again applies nothing and leaves both histories unchanged") {
            withTestDatabase { _, dataSource ->
                val lifecycle = MigrationLifecycle(dataSource, testApplication)
                lifecycle.migrate()
                val runtimeHistory = dataSource.history(RuntimeMigrations.SCHEMA)
                val applicationHistoryRows = dataSource.history(TEST_APPLICATION_SCHEMA)

                lifecycle.migrate()
                MigrationLifecycle(dataSource, testApplication).migrate()

                dataSource.history(RuntimeMigrations.SCHEMA) shouldBe runtimeHistory
                dataSource.history(TEST_APPLICATION_SCHEMA) shouldBe applicationHistoryRows
            }
        }

        test("instances migrating a fresh database at the same time apply each migration exactly once, in order") {
            withTestDatabase { database, _ ->
                val instances = 4
                val barrier = CyclicBarrier(instances)
                val executor = Executors.newFixedThreadPool(instances)
                try {
                    // Each instance has its own pool, as separate processes would. The application
                    // migration depends on the runtime one, so running it early would fail.
                    val runs =
                        (1..instances).map { instance ->
                            executor.submit {
                                createDataSource(database.configuration, poolName = "instance-$instance").use { dataSource ->
                                    barrier.await()
                                    standInLifecycle(dataSource, dependentApplication).migrate()
                                }
                            }
                        }
                    runs.forEach { shouldNotThrowAny { it.get(2, TimeUnit.MINUTES) } }
                } finally {
                    executor.shutdownNow()
                }

                createDataSource(database.configuration, poolName = "verification").use { dataSource ->
                    dataSource.appliedVersions(RuntimeMigrations.SCHEMA) shouldContainExactly listOf("1")
                    dataSource.appliedVersions(TEST_APPLICATION_SCHEMA) shouldContainExactly listOf("1")
                }
            }
        }

        test("a runtime stream that fails validation stops the lifecycle before the application stream") {
            withTestDatabase { _, dataSource ->
                RuntimeMigrations(dataSource).migrate()
                // As if a released runtime migration had been edited after it was applied.
                dataSource.execute("UPDATE commerce.$HISTORY_TABLE SET checksum = checksum + 1 WHERE version IS NOT NULL")

                shouldThrow<FlywayException> {
                    MigrationLifecycle(dataSource, testApplication).migrate()
                }.message shouldContain "checksum mismatch"

                dataSource.relationExists("public.$HISTORY_TABLE") shouldBe false
                dataSource.schemaExists(TEST_APPLICATION_SCHEMA) shouldBe false
                dataSource.relationExists("$TEST_APPLICATION_SCHEMA.test_application_records") shouldBe false
            }
        }

        test("a failed application migration is not recorded, and the runtime migrations stay applied") {
            withTestDatabase { _, dataSource ->
                shouldThrow<FlywayException> {
                    MigrationLifecycle(dataSource, testApplicationMigrations("classpath:db/testapp-broken")).migrate()
                }.message shouldContain "V1__test_application_broken.sql"

                dataSource.appliedVersions(RuntimeMigrations.SCHEMA).shouldNotBeEmpty()
                dataSource.appliedVersions(TEST_APPLICATION_SCHEMA).shouldBeEmpty()
                dataSource.relationExists("$TEST_APPLICATION_SCHEMA.test_application_broken") shouldBe false
            }
        }

        test("validation applies nothing and fails until both streams are fully applied") {
            withTestDatabase { _, dataSource ->
                val lifecycle = MigrationLifecycle(dataSource, testApplication)

                shouldThrow<FlywayException> { lifecycle.validate() }
                dataSource.schemaExists(RuntimeMigrations.SCHEMA) shouldBe false
                dataSource.schemaExists(TEST_APPLICATION_SCHEMA) shouldBe false

                RuntimeMigrations(dataSource).migrate()
                shouldThrow<FlywayException> { lifecycle.validate() }
                dataSource.relationExists("public.$HISTORY_TABLE") shouldBe false
                dataSource.relationExists(applicationHistory) shouldBe false

                lifecycle.migrate()
                shouldNotThrowAny { lifecycle.validate() }
            }
        }

        test("validation accepts migrations applied by a later release") {
            withTestDatabase { _, dataSource ->
                MigrationLifecycle(dataSource, testApplication).migrate()
                // As if a later application release had applied V2 already.
                dataSource.execute(
                    "INSERT INTO $applicationHistory " +
                        "(installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) " +
                        "VALUES (100, '2', 'later release', 'SQL', 'V2__later_release.sql', 0, current_user, 0, true)",
                )

                shouldNotThrowAny { MigrationLifecycle(dataSource, testApplication).validate() }
            }
        }

        test("application locations that would also discover the runtime migrations are rejected") {
            listOf(
                "classpath:db/commerce",
                "db/commerce",
                "classpath:/db/commerce/",
                "classpath:db",
                "classpath:db/commerce/nested",
                "classpath:",
            ).forEach { location ->
                shouldThrow<IllegalArgumentException> {
                    ApplicationMigrations("fionas", listOf(location))
                }.message shouldContain "overlaps commerce-runtime's own migrations"
            }

            listOf("classpath:db/migration", "db/commerce-application", "filesystem:db")
                .forEach { location -> shouldNotThrowAny { ApplicationMigrations("fionas", listOf(location)) } }
        }

        context("the application owns its migration schema and the history in it") {
            test("from a clean database, each owner has its schema and its own history, and public holds none") {
                withTestDatabase { _, dataSource ->
                    dataSource.schemaExists(RuntimeMigrations.SCHEMA) shouldBe false
                    dataSource.schemaExists(TEST_APPLICATION_SCHEMA) shouldBe false

                    MigrationLifecycle(dataSource, testApplication).migrate()

                    dataSource.schemaExists(RuntimeMigrations.SCHEMA) shouldBe true
                    dataSource.schemaExists(TEST_APPLICATION_SCHEMA) shouldBe true
                    dataSource.relationExists("commerce.$HISTORY_TABLE") shouldBe true
                    dataSource.relationExists(applicationHistory) shouldBe true
                    dataSource.relationExists("public.$HISTORY_TABLE") shouldBe false
                    dataSource
                        .strings("SELECT schemaname FROM pg_tables WHERE tablename = '$HISTORY_TABLE' ORDER BY schemaname")
                        .shouldContainExactly(listOf("commerce", TEST_APPLICATION_SCHEMA))

                    // The application's SQL ran, and its table lives in the application's schema.
                    dataSource.relationExists("$TEST_APPLICATION_SCHEMA.test_application_records") shouldBe true
                    dataSource.relationExists("public.test_application_records") shouldBe false
                    // Independent histories and version spaces.
                    dataSource.appliedVersions(RuntimeMigrations.SCHEMA) shouldContainExactly
                        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
                    dataSource.appliedVersions(TEST_APPLICATION_SCHEMA) shouldContainExactly listOf("1")
                }
            }

            test("the schema is whatever the application declares; the runtime knows no application schema name") {
                withTestDatabase { _, dataSource ->
                    val elsewhere = ApplicationMigrations("another_application", listOf("classpath:db/testapp-unqualified"))

                    MigrationLifecycle(dataSource, elsewhere).migrate()

                    dataSource.appliedVersions("another_application") shouldContainExactly listOf("1")
                    dataSource.relationExists("another_application.$HISTORY_TABLE") shouldBe true
                    dataSource.schemaExists(TEST_APPLICATION_SCHEMA) shouldBe false
                    dataSource.relationExists("public.$HISTORY_TABLE") shouldBe false
                }
            }

            test("an unqualified application migration resolves against the application's schema, not public") {
                withTestDatabase { _, dataSource ->
                    MigrationLifecycle(dataSource, testApplicationMigrations("classpath:db/testapp-unqualified")).migrate()

                    dataSource.relationExists("$TEST_APPLICATION_SCHEMA.test_application_unqualified") shouldBe true
                    dataSource.relationExists("public.test_application_unqualified") shouldBe false
                }
            }

            test("a schema that already exists but is empty is used as it is") {
                withTestDatabase { _, dataSource ->
                    dataSource.execute("CREATE SCHEMA $TEST_APPLICATION_SCHEMA")

                    MigrationLifecycle(dataSource, testApplication).migrate()

                    dataSource.appliedVersions(TEST_APPLICATION_SCHEMA) shouldContainExactly listOf("1")
                    dataSource.relationExists("public.$HISTORY_TABLE") shouldBe false
                }
            }

            test("a schema that already holds tables but no history is refused, never baselined") {
                withTestDatabase { _, dataSource ->
                    dataSource.execute("CREATE SCHEMA $TEST_APPLICATION_SCHEMA")
                    dataSource.execute("CREATE TABLE $TEST_APPLICATION_SCHEMA.preexisting (id int PRIMARY KEY)")

                    shouldThrow<FlywayException> { MigrationLifecycle(dataSource, testApplication).migrate() }
                        .message shouldContain "non-empty schema"

                    dataSource.appliedVersions(TEST_APPLICATION_SCHEMA).shouldBeEmpty()
                    dataSource.relationExists("$TEST_APPLICATION_SCHEMA.test_application_records") shouldBe false
                }
            }

            test("an installation with its application history in public is not migrated, copied, or reinterpreted") {
                withTestDatabase { _, dataSource ->
                    // As left by an earlier runtime: application history in public.flyway_schema_history.
                    dataSource.execute("CREATE SCHEMA $TEST_APPLICATION_SCHEMA")
                    Flyway
                        .configure()
                        .dataSource(dataSource)
                        .schemas("public")
                        .table(HISTORY_TABLE)
                        .locations("classpath:db/testapp")
                        .load()
                        .migrate()
                    val legacyHistory = dataSource.history("public")
                    RuntimeMigrations(dataSource).migrate()

                    // The application's schema now holds its tables but no history of its own, so the
                    // new stream refuses to start: it neither adopts the legacy history nor re-runs V1.
                    shouldThrow<FlywayException> { MigrationLifecycle(dataSource, testApplication).migrate() }
                        .message shouldContain "non-empty schema"
                    shouldThrow<FlywayException> { MigrationLifecycle(dataSource, testApplication).validate() }

                    dataSource.history("public") shouldBe legacyHistory
                    dataSource.appliedVersions(TEST_APPLICATION_SCHEMA).shouldBeEmpty()
                }
            }

            test("the schema and locations are validated when the migrations are declared") {
                listOf("", "Fionas", "1fionas", "fi-onas", "fionas.app", " fionas", "a".repeat(64)).forEach { schema ->
                    shouldThrow<IllegalArgumentException> { ApplicationMigrations(schema, testApplication.locations) }
                        .message shouldContain "lower-case PostgreSQL identifier"
                }
                shouldThrow<IllegalArgumentException> { ApplicationMigrations("commerce", testApplication.locations) }
                    .message shouldContain "owned by commerce-runtime"
                listOf("public", "information_schema", "pg_catalog", "pg_temp").forEach { schema ->
                    shouldThrow<IllegalArgumentException> { ApplicationMigrations(schema, testApplication.locations) }
                        .message shouldContain "shared or system schema"
                }
                shouldThrow<IllegalArgumentException> { ApplicationMigrations("fionas", emptyList()) }
                    .message shouldContain "at least one location"

                shouldNotThrowAny { ApplicationMigrations("fionas", testApplication.locations) }
                shouldNotThrowAny { ApplicationMigrations("a".repeat(63), testApplication.locations) }
                ApplicationMigrations("fionas", testApplication.locations) shouldBe
                    ApplicationMigrations("fionas", testApplication.locations)
            }
        }
    })
