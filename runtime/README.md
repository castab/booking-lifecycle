# commerce-runtime

```text
io.github.castab:commerce-runtime:<version>
```

The opinionated, reusable runtime of the [`commerce`](../README.md) project. Concrete
commerce applications are assembled from it. It turns the vocabulary and invariants of
[`commerce-domain`](../domain/README.md) into working infrastructure: application
operations, explicit transactions, PostgreSQL persistence, HTTP conventions on http4k and
Jetty, one error contract, health checks, authenticated principal sessions and permission
enforcement, configuration, and an explicit composition root.

```text
commerce-domain
      │
      ▼
commerce-runtime
      │
      ▼
concrete commerce application   (owns main() and the process)
```

## What it is, and what it is not

`commerce-runtime`:

- **is a library.** It applies no Gradle `application` plugin, and there is no
  `./gradlew :runtime:run`.
- **is not a complete application by itself.** It provides no default commerce
  application, and it has no production `main()` and no development entry point.
- **expects explicit application contributions.** `commerceRuntime(configuration,
  application)` has no default for `application`. Every consumer states what its
  application contributes, even when that is nothing (`ApplicationContributions()`).
- **provides opinionated HTTP and persistence infrastructure:** http4k on Jetty,
  kotlinx.serialization, HikariCP, JDBI, PostgreSQL, and Flyway.
- **starts and manages runtime resources when a concrete application invokes it.**
  `start()` starts Jetty, and `close()` stops it and closes the connection pool.
- **does not own the surrounding process or application lifecycle.** It installs no
  shutdown hook, never blocks the calling thread, and parses no command line. Those
  belong to the application's `main`.
- **defines the configuration it requires, but ships none.** The runtime declares
  `CommerceRuntimeConfiguration` and provides its Hoplite/HOCON loading machinery. The
  concrete application supplies the actual `application.conf` and deployment environment.
- **emits logs, but does not choose or configure the logging backend.** The runtime logs
  through Kotlin Logging on the SLF4J API. It selects no SLF4J provider (its published
  dependencies include no Logback or other backend) and ships no `logback.xml`. The
  concrete application owns the provider and its configuration.

It is also not a specific business's backend. Catering, mobile detailing, computer
repair, pet and service appointments, and point of sale should all be able to use it.
Anything that makes sense for only one of them belongs to that application.

> **Status: foundation.** This iteration establishes the architecture and the
> infrastructure: configuration, persistence and transactions, migrations, HTTP, error
> handling, health, principal sessions and authorization, and the application-contribution
> seam. It does not yet persist or
> orchestrate financial documents or payments, and it never owns
> application entities such as customers or bookings. See
> [Current limitations](#current-limitations).

## Composing an application

A concrete application depends on `commerce-runtime` and owns its entry point, its
`application.conf`, and its logging configuration:

```kotlin
// In the concrete application, not in commerce-runtime.
fun main() {
    val application =
        ApplicationContributions(
            migrations = ApplicationMigrations(schema = "myapp", locations = listOf("classpath:db/migration")),
            routes = { context -> listOf(myApplicationRoutes(context.transactor)) },
        )

    val runtime =
        commerceRuntime(
            configuration = CommerceRuntimeConfiguration.load(), // the application's application.conf
            application = application,
        )

    runtime.start()

    // The application owns its process lifecycle, for example:
    Runtime.getRuntime().addShutdownHook(Thread { runtime.close() })
    Thread.currentThread().join()
}
```

An application with nothing to add, such as a point-of-sale application that uses only
the commerce capabilities, still passes `ApplicationContributions()` explicitly. That is
its decision, not a runtime default.

`commerceRuntime(...)` in
[`CommerceRuntime.kt`](src/main/kotlin/io/github/castab/commerce/runtime/CommerceRuntime.kt)
builds, in order:

```text
configuration → DataSource (HikariCP) → migration phase (runtime, then application)
             → Jdbi → Transactor + repositories → operations
             → commerce and application routes → error handling → http4k → Jetty
```

Nothing after the migration phase is built until it succeeds. See
[Database and migrations](#database-and-migrations).

The server is created but not started. `CommerceRuntime.start()` starts Jetty and returns
immediately. `CommerceRuntime.close()` stops Jetty and closes the pool.
`CommerceRuntime.http` is the complete HTTP handler, usable without a server.

### Application contributions

```text
Concrete application
        │
        │ ApplicationContributions
        ▼
commerce-runtime
        │
        ├── commerce routes
        ├── application routes
        ├── runtime migrations         (owned and discovered by commerce-runtime)
        ├── application migrations     (owned by the application, orchestrated by the runtime)
        ├── transactions
        └── shared infrastructure
```

`ApplicationContributions` is intentionally small and not booking-specific. An
application contributes its own migrations (the PostgreSQL schema it owns and the Flyway
locations of its scripts, never the runtime's), its own routes, and software-defined `permissionDefinitions`. The routes are built from
the shared `CommerceRuntimeContext`, which holds configuration, the `Transactor`, the
offerings snapshot repository, `context.sessions`, and `context.authorization`. The
runtime owns the error handling around every route.

### Application entities and the shared transaction

The runtime owns no customer, booking record, inquiry, or other application data model,
and no customer persistence, customer CRUD, or customer endpoints. Those entities, and
their relationships to commerce facts, belong to the concrete application. What the
runtime provides is the transaction those relationships are written in:

```text
Application operation
        │
        ▼
runtime Transactor
        │
        ├──────────────► application repositories
        │
        └──────────────► OfferingsSnapshotRepository
```

`commerce-runtime` owns this shared transaction abstraction, and application repositories
use the same `Transaction`. Repositories never open their own transactions.

`OfferingsSnapshotRepository` is the first commerce-owned repository. An application
can save an offerings catalog revision and write its own catalog or audit row in the same
transaction. The PostgreSQL integration tests prove that both writes commit or roll back
together across the `commerce` and application schemas. Neither module knows the
application's relationships.

### Provisional extension seam

`ApplicationContributions` and `CommerceRuntimeContext` are the **provisional**
application-extension seam. They are enough for an application to run on the runtime
today, but they are not the settled extension contract. Expect them to change, without a
long deprecation period, once the [booking extension](#booking-extension-direction) and
further capabilities are designed from the requirements of real consumers. Whether the
seam becomes something like `CommerceApplication`, `CommerceExtension`, or a
booking-specific extension is undecided. New contribution points or context members are
added only when a concrete consumer needs them.

### Infrastructure types in the public API

Some JDBI and HikariCP types are deliberately part of the public API:

| Public member | Exposed type | Why |
|---|---|---|
| `Transaction.handle` | `org.jdbi.v3.core.Handle` | Lets application repositories write inside the same transaction as commerce repositories. |
| `createDataSource(...)` | `com.zaxxer.hikari.HikariDataSource` | The runtime's connection pool, for application tooling that needs the same pool configuration. |

For that reason `jdbi3-core` and `HikariCP` are `api` dependencies, alongside `http4k-core`,
`http4k-format-kotlinx-serialization`, and `kotlinx-serialization-json`. This exposure
follows from the opinionated PostgreSQL/JDBI stack and is **intentional but revisitable**.
A later iteration may narrow it, for example by wrapping the handle in a commerce-owned
type. Code against it knowingly. It will not be expanded casually: Flyway, Jetty, and
Hoplite stay internal, and any further infrastructure exposure is a deliberate, documented
decision (see [`AGENTS.md`](../AGENTS.md#provisional-application-extension-seam)).

## Relationship to commerce-domain

- `commerce-runtime` depends on `commerce-domain` (as an `api` dependency, at the same
  version); `commerce-domain` never depends on `commerce-runtime`.
- The runtime reuses domain types directly. It never duplicates them, and it never
  annotates them for serialization or persistence.
- Relationships that only coordinate independently meaningful concepts, for example which
  financial documents belong to a booking or a customer, are owned by the concrete
  application, which can persist them in the runtime's shared transaction. Neither the
  domain nor the runtime holds them:

  > A relationship belongs in `commerce-domain` when one domain concept cannot meaningfully
  > express its semantics or invariants without the other concept. Relationships that
  > coordinate otherwise independently meaningful concepts belong to the consuming
  > application layer.

- Where the domain already defines a boundary contract (for example the
  `FinancialDocumentHistory` SPI or the staff resolver ports), the runtime implements that
  contract rather than wrapping it.

## Technology

The stack follows the reference backend (`castab/fionas-ui` `apps/backend`), with versions
declared in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml):

| Concern | Choice |
|---|---|
| Language and platform | Kotlin 2.4.20 on Java 25 |
| HTTP | http4k 6.58 on Jetty (`JettyLoom`, virtual threads; Jetty keeps Server-Sent Events possible) |
| Serialization | kotlinx.serialization 1.11 through `http4k-format-kotlinx-serialization` |
| Database | PostgreSQL (driver 42.7) through HikariCP 7.1 and JDBI 3.54 |
| Migrations | Flyway 13.3 |
| Configuration | Hoplite 2.9 with HOCON, plus explicit environment overrides |
| Logging | Kotlin Logging 8 on the SLF4J 2 API; no provider is selected (the runtime's own tests use Logback 1.6) |
| Tests | Kotest 6.2, real PostgreSQL through the Docker CLI |
| Formatting | ktlint (the repository's single formatter) |

There is no Spring, Spring Boot, Hibernate, JPA, Micronaut, Quarkus, Ktor, or dependency
injection framework. Everything is wired by ordinary Kotlin in one visible composition
root.

## Architecture

```text
HTTP route            translate: request DTO -> domain values -> operation -> response DTO
  │
  ▼
operation              orchestration and policy; opens the transaction
  │
  ├── commerce-domain  invariants and legal transitions
  ├── repositories     SQL, inside the caller's transaction
  └── Transactor       one explicit PostgreSQL transaction boundary
```

Routes contain no SQL and no orchestration. Operations contain no HTTP. Repositories
never open their own transactions.

### Packages

| Package (`io.github.castab.commerce.runtime...`) | Contents |
|---|---|
| `runtime` | `commerceRuntime(...)`, `CommerceRuntime`, `ApplicationContributions`, and `CommerceRuntimeContext` (configuration, `Transactor`, commerce repositories, `financialLedger`, `sessions`, `authorization`, `serviceCredentials`, and `serviceAccessTokens`). |
| `runtime.config` | `CommerceRuntimeConfiguration`: HOCON loading, environment overrides, and validation. |
| `runtime.persistence` | `createDataSource` (HikariCP), `MigrationLifecycle` (the migration phase), `Transactor`, `Transaction` and `TransactionIsolation`, offerings, financial-document, and payment repositories, internal session, authorization, and service credential repositories, and PostgreSQL error helpers. |
| `runtime.financial` | `FinancialLedger`: financial-document reads and append operations, payment recording and allocation, refunds with their refund allocations, derived payment and document reconciliation, and `PaymentHistory` reads of a payment or a document lineage's payments. |
| `runtime.authorization` | `AuthorizationDirectory`, `PermissionCatalog`, `RuntimePermissions` and `commercePermissionDefinitions`, and the opt-in authorization administration (including service credential administration), permission catalog, and current principal HTTP capabilities and DTOs. |
| `runtime.serviceauth` | `ServiceCredentials`, `ServiceCredential`, `ServiceCredentialSecret`, `ServiceAccessTokens`, `ServiceAccessToken`, `ServiceAccessTokenAuthenticator`, `serviceAccessTokenAuthentication`, the token endpoint capability, DTOs, and `serviceAccessTokenOpenApiSecurity`. |
| `runtime.session` | `SessionManager`, `PrincipalSession`, `SessionId`, `SessionToken`, `IssuedSession`, token extractors (`BearerSessionToken`, `SessionCookie`), `SessionAuthenticator`, and the `sessionAuthentication` filter. |
| `runtime.operation` | Support for operations (use cases such as issuing an invoice or recording a payment): `CommerceFailure`, the expected failures of operations, and `validating`. |
| `runtime.http` | `CommerceJson`, `jsonBody`, the error contract and `CommerceErrorHandling` filter, health routes, the `authenticatedPrincipal` request lens, authentication composition (`RequestAuthenticator`, `authentication`), and the authorization filters (`requirePermission`, `requireAuthenticatedPrincipal`, `AccessControl`). |

Booking orchestration will appear when it has a concrete generic use case.

## Runtime conventions

### Configuration

Ownership is split deliberately:

- **commerce-runtime** defines the configuration it requires (`CommerceRuntimeConfiguration`,
  with defaults and validation) and provides the loading machinery
  (`CommerceRuntimeConfiguration.load()`: Hoplite/HOCON plus environment overrides). The
  published jar contains no `application.conf`.
- **The concrete application** supplies the deployment configuration: its own
  `application.conf` on the classpath, plus the environment. It may also construct
  `CommerceRuntimeConfiguration` directly and skip `load()` altogether.

`load()` reads the application's classpath resource `/application.conf` (or another
resource the application names) and fails clearly if it is missing. Settings the file
omits take the model's defaults. The file must declare the `database` block, although its
connection values may be empty placeholders when the environment supplies them, as in this
minimal application file:

```hocon
database {
  jdbcUrl = ""
  username = ""
  password = ""
}
```

`load()` then applies exactly these environment variables:

| Setting | Environment variable | Default |
|---|---|---|
| `server.port` | `PORT` | `8080` |
| `database.jdbcUrl` | `DATABASE_JDBC_URL` | required |
| `database.username` | `DATABASE_USERNAME` | required |
| `database.password` | `DATABASE_PASSWORD` | required |
| `database.maximumPoolSize` | `DATABASE_MAXIMUM_POOL_SIZE` | `4` |
| `database.minimumIdle` | `DATABASE_MINIMUM_IDLE` | `1` |
| `database.connectionTimeoutMs` | `DATABASE_CONNECTION_TIMEOUT_MS` | `500` |
| `database.validationTimeoutMs` | `DATABASE_VALIDATION_TIMEOUT_MS` | `1000` |
| `migrations.onStartup` | `MIGRATIONS_ON_STARTUP` (`migrate` or `validate`) | `VALIDATE` |
| `sessions.lifetimeMinutes` | `SESSIONS_LIFETIME_MINUTES` | `720` (12 hours) |
| `serviceTokens.signingKey` | `SERVICE_TOKENS_SIGNING_KEY` | none; enables service access tokens |
| `serviceTokens.lifetimeMinutes` | `SERVICE_TOKENS_LIFETIME_MINUTES` | `15` |
| `serviceTokens.issuer` | `SERVICE_TOKENS_ISSUER` | none; required when service tokens are configured |

`sessions.lifetimeMinutes` is the fixed lifetime of every session, between 1 minute and 1
year; see [Sessions and authorization](#sessions-and-authorization). It is the only session
setting: cookie names and attributes are chosen in code by the application.

`serviceTokens` configures [service access tokens](#service-authentication) and is optional:
it exists when the file declares a `serviceTokens` block or the environment supplies
`SERVICE_TOKENS_SIGNING_KEY`. The signing key is base64 (standard or URL-safe) of at least
32 random bytes, for example `openssl rand -base64 32`. It has no default and the runtime
never generates one; every instance of a deployment shares it, so tokens survive restarts
and are accepted by every instance. The lifetime is between 1 and 60 minutes.

The issuer is **required** once service tokens are configured, and has no default. It names
the deployment, not the library: for example `orders-production` and `orders-staging`. It
is both the `iss` and the `aud` of every token, so a token issued by one deployment is
rejected by another even if the two share a signing key by mistake. Give every deployment
its own key *and* its own issuer; the naming scheme is the deployment's. Leading or
trailing whitespace is rejected.

| Configuration | Result |
|---|---|
| No `serviceTokens` block and no `SERVICE_TOKENS_SIGNING_KEY` | Service tokens disabled; no issuer needed |
| Signing key (file or environment) without an issuer | Fails: `SERVICE_TOKENS_ISSUER is required when service tokens are configured` |
| `SERVICE_TOKENS_ISSUER` or `SERVICE_TOKENS_LIFETIME_MINUTES` without a signing key | Fails rather than being ignored |
| Signing key and explicit issuer | Valid |

A file that declares a `serviceTokens` block declares both `signingKey` and `issuer`
(empty placeholders are fine when the environment fills them), like the `database` block.
Without `serviceTokens`, service credentials can still be administered, but an application
that composes the token endpoint or token authentication fails at startup. The signing key
is redacted from the configuration's `toString`.

Invalid values fail with a message naming the variable. `load()` validates, and
`commerceRuntime(...)` validates again before it opens the connection pool or anything
else, so a configuration constructed in Kotlin is held to exactly the same rules. Secrets
come only from the environment. The database password is redacted from the
configuration's `toString`.

### Database and migrations

**The runtime owns migration orchestration. Each participant owns its own migrations.** A
runtime version and the database shape it requires are one compatibility unit.

| | Runtime migrations | Application migrations |
|---|---|---|
| Owner | commerce-runtime | the concrete application |
| Objects | the `commerce` schema and everything in it | the application's own schema and objects |
| Discovered at | `classpath:db/commerce`, inside the runtime jar, internally | `ApplicationContributions.migrations.locations` |
| Schema history | `commerce.flyway_schema_history` | `<schema>.flyway_schema_history`, in the application's declared schema |
| Runs | first | only after the runtime migrations succeeded |

- **Two independent streams.** Each has its own history and version space: the
  application's `V1` coexists with the runtime's `V1`, and an application never numbers
  its migrations after the runtime's. Upgrading `commerce-runtime` is enough to pick up its
  new migrations. The application never lists them, and a contributed location that
  overlaps `db/commerce` (such as `classpath:db`) is rejected.
- **The application declares its migration schema.** `ApplicationMigrations(schema, locations)`
  names the one schema the application owns; the runtime knows no application schema name.
  A schema that owns application data also owns the Flyway history that describes it, so
  the application's history is `<schema>.flyway_schema_history` and no application migration
  metadata is ever left in `public`. The runtime creates the schema when it does not exist
  (Flyway's `createSchemas`, before it records anything there), so a clean database needs no
  preparation, and it configures Flyway with that schema as its default and only managed
  schema. Consequently an unqualified `CREATE TABLE foo (...)` in an application migration
  resolves into the application's schema, not `public`; prefer qualifying names anyway, and
  always qualify references to runtime-owned objects (`commerce.<table>`). The schema must
  be a lower-case identifier and cannot be `commerce`, `public`, `information_schema`, or a
  `pg_` schema. An application with no migrations contributes none (the default, `null`) and
  has no schema or history managed by the runtime.
- **Installations that predate this rule.** Earlier versions kept the application's history in
  `public.flyway_schema_history`. The runtime does not copy, rename, baseline, or reinterpret
  it, and never enables `baselineOnMigrate`. Where the application's tables already sit in the
  declared schema, Flyway refuses to migrate or validate that non-empty schema without a
  history there, and startup fails instead of re-running migrations. Recreate the database, or
  move the history deliberately as a one-time operation owned by the application.
- **Ownership.** Sharing one database is not shared ownership. Runtime migrations change
  only runtime-owned objects and never touch application tables, indexes, constraints,
  sequences, or views. Application migrations may *reference* runtime-owned objects (for
  example a foreign key to the key of a `commerce` table) but never alter their DDL. They
  may remove stale grants for an application-defined permission from
  `commerce.role_permissions` before startup catalog validation. This narrow data cleanup
  does not grant schema ownership. The boundary is enforced by review, not SQL inspection.
- **Explicit ordering.** `MigrationLifecycle.migrate()` validates and migrates the runtime
  stream, then validates and migrates the application stream, so application migrations
  may depend on objects the runtime migrations just created.
- **Startup gating.** `commerceRuntime(...)` runs the migration phase before building
  anything else. With `migrations.onStartup = MIGRATE`, it applies both streams. With
  `VALIDATE`, the default, it applies nothing and fails unless both streams are fully
  applied, accepting migrations newer than itself. Any failure throws Flyway's exception,
  which names the script and carries the database error, and returns no runtime, so no
  server starts against an incompatible database. After a runtime-stream failure, the
  application stream is not attempted.
- **Separate migration step.** The phase does not need the HTTP runtime. A release job can
  migrate first and then deploy instances that only validate:

  ```kotlin
  // In the application's migration entry point: migrate, then exit. No Jetty is started.
  createDataSource(configuration.database).use { dataSource ->
      MigrationLifecycle(dataSource, application.migrations).migrate()
  }
  ```

- **Concurrent instances.** Several instances may migrate the same database at once.
  Flyway serializes each stream with a PostgreSQL advisory lock keyed by its history
  table. One instance migrates, the others wait and then find nothing pending. A waiting
  instance gives up, and fails to start, after Flyway's lock retry limit (50 one-second
  attempts by default), so long migrations belong in a separate migration step.
- **Published database contract.** Runtime-owned structures that applications may
  reference are part of the runtime's public contract, like its Kotlin API. Runtime
  migrations preserve that contract across compatible releases, and destructive changes
  require an explicit compatibility transition: **expand** (add the new structure and keep
  the old), **migrate** (move the runtime and consumers to the new one), then **contract**
  (remove the old structure once compatibility has been deliberately ended). Nothing
  published is dropped in the release that introduces its replacement.
- **Immutable history.** Released versioned migrations are never edited. A correction is a
  new migration.
- Every SQL statement names the `commerce` schema explicitly. PostgreSQL's default
  `search_path` starts with `"$user"`, so a role named `commerce` would otherwise resolve
  unqualified names into the commerce schema. Application migrations run with the
  application's declared schema as their default schema.

The runtime migration stream starts with `V1__commerce_baseline.sql` and adds the
offerings tables in `V2__offerings_snapshots.sql`, which created
`commerce.offerings_snapshots`, `commerce.offering_categories`, and `commerce.offerings`;
`V9__aggregate_snapshots.sql` later replaced the two child tables with a JSONB column (see
[Aggregate snapshot persistence](#aggregate-snapshot-persistence)), and
`V12__offerings_current_catalogs.sql` replaced `commerce.offerings_snapshots` with
`commerce.offerings_catalogs`, one row per catalog (see [Offerings catalogs](#offerings-catalogs)).
`V3__principal_sessions.sql` creates `commerce.principal_sessions` for
[sessions](#sessions-and-authorization): the session ID, the principal as a
runtime-controlled kind (`USER` or `SERVICE`) plus its UUID, the unique SHA-256 token
digest (never the token), and the creation, expiry, and revocation times, with indexes for
revoking a principal's sessions and for finding expired ones. Development databases that
applied an unreleased draft of `V3` (with lowercase principal kinds) fail Flyway validation
on its changed checksum and must be recreated; repairing the history alone would leave
the draft's constraint in place. These are runtime-owned
published structures; later changes follow the compatibility contract above.

`V4__authorization_directory.sql` adds principals, users, service identities, roles,
role permissions, and principal-role assignments. It leaves released migrations intact.
The principal kind uses the same explicit `USER`/`SERVICE` mapping as sessions. Foreign
keys reject assignments to missing principals or roles. Role deletion never cascades.

`V5__financial_ledger.sql` added immutable financial-document snapshots and ordered line
items (`V9` moved those into the snapshot row), payment records, and allocations. The snapshot key is `(document_id, version)`;
the predecessor reference and unique `(document_id, previous_version)` reject gaps and
competing successors. Allocations have foreign keys to their payment and the exact
document snapshot. `(external_provider, external_reference)` is unique for payments.
The payment method check allows exactly `CASH`, `CHECK`, `CARD`, `BANK_TRANSFER`,
`DIGITAL_WALLET`, and `OTHER`, matching `PaymentMethod`; adding a method requires a
migration that widens the check constraint.

`V6__refunds.sql` adds `commerce.refund_records` and `commerce.refund_allocations` and
leaves `V5` unchanged. A refund row references its payment
(`refund_records.payment_id → payment_records`) and stores the amount (strictly positive),
currency, method (the same check as payments), refund time as epoch seconds plus
nanoseconds, and an optional external provider and reference. The pair is either absent
or both non-blank, and `(external_provider, external_reference)` is unique. A refund
allocation row references its refund (`refund_id → refund_records`) and the payment
allocation it unwinds (`payment_allocation_id → payment_allocations`) and stores the
amount (strictly positive), currency, and allocation time. Neither table duplicates the
payment or document id: they are recovered through the referenced rows. Indexes cover a
payment's refunds and a refund's, or an allocation's, refund allocations. There is no
status, balance, or other mutable column.

`V7__financial_document_created_at.sql` adds
`commerce.financial_document_snapshots.created_at timestamptz NOT NULL` with a
`clock_timestamp()` default. PostgreSQL assigns the instant once at each snapshot insert;
reads never generate it. V7 checks for preexisting financial snapshots before altering
the table and fails if any exist. No migration-time timestamp is fabricated; ephemeral
pre-V7 databases with financial snapshots must be recreated.

`V10__service_credentials.sql` adds `commerce.service_credentials` for
[service authentication](#service-authentication): the credential ID (primary key, used to
find a presented credential), the service as `principal_kind` (checked to be `SERVICE`)
plus its UUID with a foreign key to `commerce.service_identities`, so a user can never hold
a credential, a required label, an Argon2id PHC hash of the secret (never the secret), and
the creation and revocation times. An index lists one service's credentials. It adds a
table only and leaves earlier migrations unchanged.

> **Pre-release reset.** 0.0.4 created `commerce.customers` and 0.0.5 dropped it again.
> Before any real consumer existed, those two migrations were collapsed into the `V1`
> baseline. Databases migrated by 0.0.4 or 0.0.5 fail validation against this release and
> must be recreated. This was a one-time exception to the immutable-history rule.

### Aggregate snapshot persistence

A row represents an independently addressable fact, entity, or version. An immutable value
that exists only as a component of its parent snapshot is stored with that snapshot.
`V9__aggregate_snapshots.sql` applies that rule to the two snapshot families whose
children had no identity of their own:

| Table | Relational identity | Contents column |
|---|---|---|
| `commerce.financial_document_snapshots` | `(document_id, version)`, `previous_version`, `stage`, `created_at` | `lines jsonb NOT NULL`: the ordered line items |
| `commerce.offerings_catalogs` (since V12) | `catalog_id`, `revision` | `catalog jsonb NOT NULL`: the current ordered categories and offerings, and the retired entries |

`commerce.financial_document_lines`, `commerce.offering_categories`, and
`commerce.offerings` are gone, and V12 replaced V9's `commerce.offerings_snapshots`. Payments, allocations, refunds, principals, roles, and
sessions are independent facts and remain relational; `commerce.payment_allocations` still
references `(document_id, version)`. Reading a snapshot is one query for one row, and a
history read is one query for the lineage.

The stored JSON is a durable representation, written through runtime-internal DTOs and
never by serializing domain types:

```jsonc
// lines: array order is line order
[{"id": "<uuid>", "description": "Labor", "subDescription": null, "quantity": "2.500",
  "priceAmount": "12.3400", "taxAmount": "1.0300", "currency": "USD"}]

// catalog
{"categories": [{"key": "tier", "displayName": "Tier", "description": null,
                 "minimumSelections": 0, "maximumSelections": null}],
 "offerings": [{"key": "basic", "category": "tier", "displayName": "Basic", "description": null,
                "price": {"kind": "PER_DURATION", "amount": "50.125", "currency": "USD",
                          "seconds": 3600, "nanos": 123456789},
                "selectionState": "ENABLED", "availability": "AVAILABLE",
                "badge": null, "statusNote": null, "infoNote": null}],
 "retiredCategories": [],
 "retiredOfferings": [{"lastSeenRevision": 3, "offering": {...an offering...}}]}
```

An offering's `price` is `null`, or has a `kind` of `FIXED`, `PER_QUANTITY` (adds
`dimension`), or `PER_DURATION` (adds `seconds` and `nanos`). Decimals are plain strings, so
scale and every digit survive any JSON reader; they are never JSON numbers. Every property
is written, `null` included. Decoding is strict: an unknown property, a missing or null
required value, an unknown `kind` or enum name, a quoted number, a malformed decimal,
currency, or UUID, or a restored value that breaks a domain invariant fails with an
`IllegalStateException` that names the snapshot. Nothing is defaulted or repaired. Schema
checks only guarantee the outer shape (`lines` is an array; `catalog` is an object with
`categories`, `offerings`, `retiredCategories`, and `retiredOfferings` arrays). Unique keys, category references, line id uniqueness,
and currency agreement are enforced by the domain constructors on every read, as they are
on every write.

Key reservation and retired discovery are answered from the one catalog row, restored
strictly: retired keys must be unique, absent from the current contents, and last seen
before the current revision, so a corrupt row fails the question with an
`IllegalStateException` instead of influencing its answer. Retired values are ordered by
UTF-8 byte order, matching PostgreSQL `"C"`. There is no projection or JSONB index.

V9 does not convert populated databases. Like V7 and V8, it fails and leaves the schema
untouched when a financial snapshot or an offerings revision already exists; recreate the
ephemeral database rather than backfilling.

### Offerings catalogs

A catalog keeps only its current revision. The revision number advances on every change
and is the concurrency (`expectedRevision`) and staleness token; earlier revisions are not
retained and cannot be read back. An application that must know what an earlier catalog
said records that itself, for example in the estimate it created from an evaluation.

`CommerceRuntimeContext.offeringsSnapshotRepository` exposes the
`OfferingsSnapshotRepository`. Each method takes the caller's `Transaction` first:

```kotlin
context.transactor.inTransaction { transaction ->
    context.offeringsSnapshotRepository.save(transaction, snapshot)
    // Application-owned writes may use transaction.handle here too.
}

val latest = context.transactor.inTransaction { transaction ->
    context.offeringsSnapshotRepository.retrieveLatestVersion(transaction, catalogId)
}
```

`retrieveLatestVersion` returns the catalog's current revision or null. `save` creates a
catalog from a revision-1 snapshot or replaces the current revision with its immediate
successor; it locks the catalog row (`SELECT ... FOR UPDATE`), so concurrent writers
serialize and the later one conflicts. Saving a first revision for an existing catalog, a
successor of a revision that is no longer current, or a successor of a missing catalog is
`CommerceFailure.Conflict`. `save` also maintains the retired entries: keys absent from the
successor are retired with the replaced revision as their last-seen revision, and keys
present again are no longer retired.
Categories and offerings are stored in array order inside the catalog's one row, so round trips preserve snapshot order.
Price forms have stable `FIXED`, `PER_QUANTITY`, and `PER_DURATION` discriminators;
amounts are exact decimal strings, and durations store seconds plus nanoseconds.
The repository maps rows to domain values explicitly through `OfferingsSnapshot.restore`.
It does not open a connection or transaction.

`V8__offering_selection_and_availability.sql` added `selection_state` and `availability`
to `commerce.offerings` as `NOT NULL` text columns with no defaults. Checks accepted only
`ENABLED`/`DISABLED` and `AVAILABLE`/`UNAVAILABLE`; all four combinations are valid.
V1-V7 remain unchanged. V8 refuses preexisting offering rows because no historical
selection states exist to restore; recreate the ephemeral database instead of backfilling.
`V9` keeps both as required properties of each stored offering. The repository writes and
restores them explicitly and rejects a missing, null, or unknown stored value.

`V11__offering_badge_and_status_note.sql` changes no table. Every stored offering now has
`badge`, `statusNote`, and `infoNote` properties (`null` or nonblank text), and strict decoding cannot
read an older catalog, so V11 refuses a populated `commerce.offerings_snapshots` instead of
converting it; recreate the ephemeral database.

`V12__offerings_current_catalogs.sql` refuses a populated `commerce.offerings_snapshots`
the same way, drops it, and creates `commerce.offerings_catalogs` (`catalog_id` primary
key, `revision integer NOT NULL CHECK (revision >= 1)`, and the `catalog` JSONB).

### Offerings catalog operations and HTTP

`io.github.castab.commerce.runtime.offering` provides `CreateOfferingsCatalog`,
`AddOfferingCategory`, `AddOfferings`, `GetOfferingsCatalog`,
`ListOfferingCategories`, `GetOfferingCategory`,
`ListCategoryOfferings`, `ListOfferings`, and `GetOffering`, plus `UpdateOfferings`,
`RetireOfferings`, `RestoreOfferings`, `UpdateOfferingCategory`, `RetireOfferingCategory`,
`RestoreOfferingCategory`, `ListRetiredOfferings`, and `ListRetiredCategories`.
Every operation accepts an
explicit `OfferingsCatalogId`.
Commands each open one transaction through `Transactor`, read the latest catalog, derive
the immediate successor, and save it through `OfferingsSnapshotRepository`.
Initialization creates an empty revision 1. A missing catalog or item is `NotFound`;
duplicate keys and duplicate initialization are `Conflict`. Concurrent writers that
derive the same successor revision serialize on the catalog row, and the later one
receives `Conflict`. The runtime
does not retry or merge it; the caller may reload and decide what to do.

Offering mutations are batches. `AddOfferings`, `UpdateOfferings`, and `RestoreOfferings`
take a list of complete `Offering` values and `RetireOfferings` a list of keys; a list must
be non-empty and name no key twice (`ValidationFailed` otherwise). Every item is checked
against the same latest revision, and the first invalid item fails the whole batch with its
usual failure (`NotFound`, `Conflict`, or `ValidationFailed`), so a batch either saves all of
its items in exactly one successor revision or saves nothing. Add and restore append in
batch order, update keeps each offering's position, and retire keeps the others' order. A
one-item list is the single-offering case. Categories are changed one at a time.

Offering and category keys are durable natural identities within a catalog. Any key
that has ever been used remains reserved. Latest-snapshot absence means retired; the
catalog keeps the retired key's last representation, and restoration reactivates the same
identity with caller-supplied properties. Update never changes the key.

| Current identity | Add | Update | Retire | Restore |
|---|---|---|---|---|
| Never existed | Append | 404 | 404 | 404 |
| Active | 409 | Replace in place | Remove from successor | 409 |
| Retired | 409: restore instead | 409: restore first | 409: already retired | Append same identity |

Updates preserve list positions; unrelated items retain their relative order on retirement.
Both additions and restorations append deterministically. A category with active offerings
cannot be retired (409); the caller must explicitly retire or move its offerings first.
An offering update or restore requires a current category (404 when missing or retired).
There is no cascade, general reorder, key rename, per-item timestamp/version/UUID, or
mutable active/deleted flag.

`offeringKeyReserved(transaction, catalogId, key)` and `categoryKeyReserved(...)` answer
whether a key is active or retired. `retrieveRetiredOfferings(transaction, catalogId)` and
`retrieveRetiredCategories(...)` return each retired key's last representation as a
persistence-owned `RetiredCatalogValue<T>` whose `lastSeen` reference is the last revision
containing that key. Operations translate these into their `CatalogResult` read models, so
persistence has no dependency on `runtime.offering`. The catalog ID scopes both identity
checks and discovery. All methods read the one catalog row in the caller's transaction.

`ListRetiredOfferings` and `ListRetiredCategories` return the latest catalog reference and
those last representations in ascending key order (PostgreSQL `C` collation). HTTP returns
`{"revision": 5, "offerings": [{"lastSeenRevision": 4, "offering": {...}}]}` (or
`categories`/`category`). The current revision and last-seen revision refer to the catalog
timeline, not per-item versions. Retired discovery belongs exclusively to the managed
`ReadWrite` surface and requires `commerce.offerings.manage` through the supplied live
`AccessControl`: 401 without a principal, 403 without the permission, and 200 for a
manager. `ReadOnly` does not mount these endpoints. Ordinary reads of the current catalog
retain the host-owned read access policy. Paths use `/retired/offerings` and
`/retired/categories` to keep the legal natural key `retired` accessible on item paths.

Every mutation of an existing catalog, including `AddOfferings` and
`AddOfferingCategory`, requires `expectedRevision: OfferingsRevision` immediately after
`catalogId` in the operation signature. `CreateOfferingsCatalog` has no precondition.
There are two separate concurrency guarantees:

- **Client optimistic concurrency:** inside the mutation's transaction, read latest and
  compare its revision with the caller's expected revision before checking lifecycle
  transitions or deriving a successor. A mismatch returns `CommerceFailure.Conflict`
  (409), with the current and expected revisions and an instruction to reload. No
  successor is created and the committed values remain unchanged. Callers must acknowledge
  the latest revision before replacing, adding, retiring, or restoring anything.
- **Database successor concurrency:** two transactions can both read r12 and satisfy
  expected r12 before deriving r13. `save` locks the catalog row, so the second waits,
  then sees r13 committed and returns 409, rolling its entire transaction back. This guard
  alone does not protect against a stale browser submitting after r13 has already
  committed.

There is no automatic retry or merge. Callers reload and decide what to do. Tests cover
stale requests for all eight mutations, the stale full-replacement price regression, and
synchronized PostgreSQL writers that satisfy the same expected revision and then race to
save one successor.

The HTTP capability is opt-in. A concrete application can bind a catalog and compose its
original http4k contract routes into its own contract. A write-capable binding carries the
application's `AccessControl` (its authentication filter, bound to the runtime's authorization directory):

```kotlin
val access = AccessControl(sessionAuthentication(context.sessions, sessionCookie), context.authorization)
val catalog =
    offeringsHttpCapability(
        context,
        OfferingsHttpBinding(
            catalogId = myCatalogId,
            basePath = "/offering-catalog",
            operationIdPrefix = "primaryOfferings",
            access = OfferingsHttpAccess.ReadWrite(access), // or OfferingsHttpAccess.ReadOnly
            tags = setOf(Tag("Catalog", "Offerings catalog")), // optional OpenAPI grouping
        ),
    )
val api = contract {
    renderer = OpenApi3(ApiInfo("My application", "1"), Jackson, apiRenderer = offeringsOpenApiRenderer(Jackson))
    descriptionPath = "/openapi.json"
    routes += catalog.contractRoutes
    routes += applicationContractRoutes
}
```

The application adds `api` to `ApplicationContributions.routes`. The sample's OpenAPI
renderer uses `http4k-format-jackson` and the focused `offeringsOpenApiRenderer`
schema hook; the runtime's HTTP transport remains
`CommerceJson` with kotlinx.serialization. The public `ContractRoute` API comes from
`http4k-api-openapi`, declared as an `api` dependency. The runtime does not create the
host's aggregate OpenAPI document or Swagger UI. It never mounts these routes by default.

The host also chooses OpenAPI grouping. `OfferingsHttpBinding.tags` (http4k's
`org.http4k.contract.Tag`, with an optional description) is applied to every route of
that binding, so Swagger UI can show each catalog under its own heading. Without tags,
http4k's default applies: an untagged route is grouped under its contract root, which is
blank for a contract mounted at `/`. Blank tag names are rejected.

At the chosen base path, the capability offers:

| Method | Relative path | Purpose |
|---|---|---|
| GET, POST | `/` | Latest catalog; initialize empty catalog |
| GET, POST | `/categories` | List; append category |
| GET, PUT, DELETE | `/categories/{categoryKey}` | Read; replace properties; retire category |
| POST | `/categories/{categoryKey}/restore` | Restore retired category |
| GET | `/categories/{categoryKey}/offerings` | Ordered category offerings |
| GET, POST, PUT | `/offerings` | List; append a batch of offerings; replace a batch of offerings |
| POST | `/offerings/retire` | Retire a batch of offerings |
| POST | `/offerings/restore` | Restore a batch of retired offerings |
| GET | `/offerings/{offeringKey}` | Read one offering |
| GET | `/retired/offerings` | Last representations of retired offerings |
| GET | `/retired/categories` | Last representations of retired categories |

`OfferingsHttpAccess.ReadOnly` omits every mutation and retired-discovery route and needs
no authorization dependency. `OfferingsHttpAccess.ReadWrite(accessControl)` exposes those
management routes, and every mutation and retired discovery requires a principal holding
`CommercePermissions.OfferingsManage` (`commerce.offerings.manage`): `401 unauthenticated`
without a principal and `403 forbidden` without the permission, before the request body is
read. The runtime declares that requirement; the application's `AccessControl` supplies the
authentication and the live, catalog-bounded effective permissions that evaluate it, so which roles grant the
permission is the application's decision. A write-capable binding cannot be built without
one. Ordinary reads of the current catalog carry no permission requirement: they
are as public as the place the host mounts them, and the host may still wrap them in its
own filters. Management routes document `401` and `403` in OpenAPI. The binding's catalog
ID supplies all write targets; request DTOs have no catalog ID or successor revision.
They carry the caller's required expected revision instead. The operation ID prefix prevents collisions when
two catalogs are mounted in one host contract.

Offering add (`POST /offerings`), update (`PUT /offerings`), and restore (`POST
/offerings/restore`) take `OfferingsBatchDto`:
`{"expectedRevision": 12, "offerings": [{"key": "...", ...}, ...]}`. Each item is a complete
`OfferingDto` carrying its own key: it requires `key`, `category`, `displayName`,
`selectionState`, and `availability`, with optional `description`, `badge`, `statusNote`,
`infoNote`, and `price`. Retire (`POST /offerings/retire`) takes `RetireOfferingsDto`:
`{"expectedRevision": 12, "keys": ["...", ...]}`. Updates and restores are complete
replacements: omitted optional values reset to their defaults. Each batch applies in one
transaction and one successor revision, all or nothing; an empty batch, a repeated key, or
an invalid key is `422 validation_failed`, and any other invalid item fails the batch with
its usual status. Responses are `OfferingsDto` (`{"revision": 13, "offerings": [...]}`, 201
for add and 200 for update and restore) and `CatalogRevisionDto` for retire.

Category PUT and restore POST use `OfferingCategoryMutationDto`, with the identity taken
only from the path, and add POST uses the flat `AddOfferingCategoryDto`; all require integer
`expectedRevision`. Category bodies contain `displayName`, optional `description`,
`minimumSelections` (default 0), and `maximumSelections` (default null), also as complete
replacements. They reuse the existing DTO domain conversion and validation. Unknown
additive fields follow `CommerceJson` conventions and cannot rename the path identity.

Category DELETE uses a required integer query parameter:
`DELETE /categories/{categoryKey}?expectedRevision=12`. This is directly usable by browser
clients and declared as a required integer query parameter in OpenAPI. There is no DELETE
body or ETag machinery. Authentication and permission checks run before body/query
extraction. Missing, repeated, non-integer, or out-of-range query revisions return
`400 malformed_request`. Missing or malformed JSON revisions also return 400. Valid
integers below 1 use `OfferingsRevision.of` validation and return `422 validation_failed`.
A valid revision different from latest returns `409 conflict` without a successor.

Category update and restore respond 200 with `CategoryDto`, including successor revision,
and category DELETE responds 200 with `CatalogRevisionDto`, e.g. `{"revision": 6}`. Add
routes respond 201. Malformed mutation bodies return 400; invalid values return 422. Stable
operation IDs append `AddOfferings`, `UpdateOfferings`, `RetireOfferings`,
`RestoreOfferings`, `UpdateCategory`, `RetireCategory`, `RestoreCategory`,
`ListRetiredOfferings`, or `ListRetiredCategories` to the host's operation ID prefix.

The catalog response groups ordered offerings beneath ordered categories and includes
catalog ID, revision, and predecessor revision. Other reads and create responses include
the resulting revision. Runtime-owned DTOs explicitly translate domain values. Prices
form a `kind`-discriminated `oneOf`: `FIXED` has `amount` and `currency`;
`PER_QUANTITY` also requires an application-named `dimension`; `PER_DURATION` also
requires an ISO-8601 `interval`. `amount` is an exact decimal string and `currency`
is an ISO currency code. Each variant excludes the other variant's fields. Unknown
additive fields are ignored, as for every `CommerceJson` body, so each OpenAPI branch
forbids only the conflicting variant fields (`not` + `required`), never all additional
properties. The runtime validates the discriminator's fields and domain values.

Every offering read (catalog, category, item, and retired discovery) includes
required non-null `selectionState` and `availability` enum fields. Add, update, and restore
requests require explicit values with no deserialization defaults. Missing, null, or unknown
values return `400 malformed_request`; all four enum combinations are accepted.
The OpenAPI renderer lists both enum sets and requires both properties without a
cross-field exclusion. A disabled or unavailable offering remains active/readable;
retiring it still removes it from the successor.
Changing either property uses `UpdateOfferings`, retaining identity and position, checking
`expectedRevision`, and saving the successor. Restoring a retired key also supplies both
properties explicitly through HTTP. Kotlin `UpdateOfferings` and `RestoreOfferings` take
complete `Offering` values, and `Offering` has no defaults for either property, so every
caller states both.

Selection evaluation remains application-invoked through `OfferingsEngine`, using the
chosen snapshot. It rejects `DISABLED` with `OFFERING_DISABLED`, `UNAVAILABLE` with
`OFFERING_UNAVAILABLE`, and absent/retired keys with the existing `UNKNOWN_OFFERING`.
For an offering that is both disabled and unavailable, disabled takes precedence and only
`OFFERING_DISABLED` is reported. This chooses the rejection reason; it does not constrain
or alter either stored fact. `offeringsValidationFailed` preserves these codes in 422
structured violations. Applications own context-specific capacity/stock policy and evaluate new orders against the current catalog. Offerings carry optional
`badge`, `statusNote`, and `infoNote` text with their other presentation text; a status note neither implies
nor overrides `selectionState` or `availability`. How a client renders that text (chips,
labels, popovers) belongs to the consuming application or its BFF.

Path parameters fail the same way as bodies: a non-integer revision is
`malformed_request` (400); a revision below 1, or a category or offering key that is
blank or contains whitespace, is `validation_failed` (422); a well-formed but absent
revision, category, or offering is `not_found` (404). Each route's OpenAPI metadata lists
the error statuses among these that the route can actually return. It does not evaluate
an `OfferingsEngine` or own any application catalog contents. Lifecycle state conflicts
are `conflict` (409). Released migrations, including `V2__offerings_snapshots.sql`,
remain unchanged; V8 added strict selection and availability columns, V9 stores catalog contents in the snapshot row,
V11 adds the badge, status note, and info note properties to stored offerings,
and V12 keeps one current row per catalog.
`offeringsOpenApiRenderer` also omits `format` when http4k supplies a null format in a
schema node; it operates on schema values before OpenAPI serialization and does not
traverse example, default, const, or extension payloads as schemas.

### Financial ledger

`CommerceRuntimeContext` supplies `financialDocumentRepository`, `paymentRepository`, and
`financialLedger`. Repositories take the caller's `Transaction` and do not commit it.
Every ledger operation also has a `Transaction` overload for application-owned writes
that must commit or roll back with it. Convenience overloads open a transaction and
delegate; a caller already inside `Transactor.inTransaction` passes its transaction to
the ledger operation.

`version(reference)`, `latestVersion(id)`, and `versionHistory(id)` expose persisted
`FinancialDocumentVersion(document, createdAt)` read values. History is ordered by
document version. The domain `FinancialDocument` stays a clock-free immutable financial
fact; the runtime database owns each version's creation instant. Existing `get`, `latest`,
and `history` methods still return domain documents. The PostgreSQL repository restores
each `(document, createdAt)` pair once and unwraps `.document` for those older reads;
the public `FinancialDocumentRepository` contract remains document-only.
`FinancialDocumentRepository.asHistory(transaction)` implements the domain
`FinancialDocumentHistory` SPI for reads within that transaction. It also exposes exact,
latest, and ordered history reads. The ledger provides `create`, `get`, `latest`,
`history`, `changeOrder`, `issueQuote`, and `issueInvoice`. It calls domain transition
methods and appends successors; it never rewrites a stage. First snapshots may be
Estimates, Quotes, or Invoices. An invalid stage transition raises
`CommerceFailure.IllegalTransition`; a competing successor raises `Conflict`.

Each snapshot row stores its lines in one `lines` JSONB value: line identity, order,
description, optional sub-description and quantity, exact decimal price and tax (plain
decimal strings), and currency. It stores no derived total or balance. On retrieval the
repository rebuilds the domain snapshot, which recalculates all totals. Payment amounts use
PostgreSQL `numeric`, which preserves decimal values; timestamps store epoch
seconds plus nanoseconds.

`PaymentRecord` remains separate from `PaymentAllocation`. The ledger's `recordPayment`
can leave money unapplied. `allocatePayment` loads an exact document snapshot, locks the
payment row, checks that payment's complete history (allocations, refunds, and refund
allocations) through `PaymentReconciliation`, and appends an allocation. `recordPaymentAgainstDocument` does
the payment and allocation in one transaction. One payment can be split across documents.
The original `(document_id, version)` of every allocation remains unchanged when the
document advances. `reconcileLatest` and `reconcile(reference)` load lineage allocations
and the refund allocations that unwind them and call `FinancialDocumentReconciliation`:
gross allocated, refund allocations, net applied, and balance are derived across the
lineage, using the selected snapshot's total.

#### Refunds

A refund is money that left the business. It belongs to a payment, not to a document:

| Refund | Recorded as |
|---|---|
| entirely from the payment's unapplied value | `RefundRecord` only |
| entirely from previously allocated value | `RefundRecord` + one or more `RefundAllocation`s |
| partly unapplied, partly allocated | `RefundRecord` + one or more `RefundAllocation`s |
| split across several allocations of the payment | `RefundRecord` + one `RefundAllocation` per allocation |

`recordRefund(paymentId, refundId, amount, method, refundedAt, externalReference,
allocations)` takes application-chosen ids and timestamps. Each
`RefundAllocationPortion(id, paymentAllocationId, amount, allocatedAt)` names a persisted
payment allocation the refund unwinds and by how much. The runtime never chooses
allocations for the caller; the part of a refund not covered by portions came from the
payment's unapplied value. The operation locks the payment row, creates the domain
`RefundRecord` and `RefundAllocation`s, checks the proposed history (payment,
allocations, refunds, refund allocations) through `PaymentReconciliation`, and only then
appends the refund and every refund allocation. It returns a `RecordedRefund`. A refund
is therefore never stored without the refund allocations that make it valid: a $100
refund of a fully allocated $500 payment is rejected unless it unwinds $100 of applied
value in the same call. Like every ledger operation, it has a `Transaction` overload so
an application can record its own receipt in the same transaction.

`reconcilePayment(paymentId)` locks the payment row and reconciles its complete persisted
history:

```text
netReceived  = paymentAmount - totalRefunded
netAllocated = grossAllocated - allocationReversals - refundAllocations
unallocated  = netReceived - netAllocated
```

Refunds reduce `netReceived`. Refund allocations reduce `netAllocated` and the affected
document lineage's `netApplied`, raising its balance; a refund of unapplied value changes
no document reconciliation. Refunded money never becomes available to allocate again:
`allocatePayment` checks refunds and refund allocations too, so a $500 payment with a
$100 refund can have at most $400 allocated. Allocations and refunds lock the same payment
row, so concurrent operations on one payment are serialized, and each validates against
the other's committed facts. Allocation reversals are not persisted by the runtime; their
contribution is zero.

Failures: an unknown payment or payment allocation is `NotFound`; a refund invalid on its
own (non-positive amount, other currency, more than the payment, a refund allocation of
another payment's allocation or larger than its refund or allocation) is
`ValidationFailed`; a refund inconsistent with the payment's history (refunds beyond the
payment, refund allocations beyond their refund or reducing an allocation below zero, an
over-applied payment) is `InvariantViolated`; an existing refund id, refund allocation id,
or external refund reference is `Conflict`. Nothing is stored on any failure.
`PaymentRepository.insertRefund` enforces the same checks for callers that use the
repository directly.

#### Payment history

Payment facts are immutable and append-only, and an application should not have to keep
the ids from a mutation response to find them again. `FinancialLedger` reads them back:

| Operation | Returns |
|---|---|
| `paymentHistory(paymentId)` | the payment's `PaymentHistory`; `NotFound` for an unknown payment |
| `paymentHistoriesForLineage(documentId)` | the `PaymentHistory` of every payment ever allocated to any version of the document lineage, ordered by `receivedAt`, then payment id; `NotFound` for a missing lineage, an empty list for a lineage with no payments |
| `unappliedPayments()` | complete `PaymentHistory` values for all payments whose derived `unallocated` amount is positive, ordered by `receivedAt`, then payment id |

A `PaymentHistory` is one coherent read: the `PaymentRecord`, all its `PaymentAllocation`s
(to any document), `RefundRecord`s, and `RefundAllocation`s, and the
`PaymentReconciliation` derived from exactly those facts through the domain's
`PaymentReconciliation.reconcile`. It reuses the domain records rather than copying them,
stores no status, and cannot be publicly constructed: there is no public constructor or
`copy()`, the lists are unmodifiable copies (mutating one throws
`UnsupportedOperationException`), and the runtime derives the reconciliation from the same
facts it stores, so the two cannot disagree. It lives in the runtime because it composes
persisted facts; it is not a domain invariant. Use these two operations, not a read model
assembled from `context.paymentRepository`, for payment history. The lists are ordered
for presentation: allocations by `allocatedAt`, refunds by `refundedAt`, refund
allocations by `allocatedAt`, ties broken by id (reconciliation ignores order).

Discovery is historical. A payment is found through a lineage when it has *ever* been
allocated to it, including when refunds later unwound those allocations completely. The
history returned is always the payment's whole history, never one filtered to the lineage:
a payment split between documents A and B is returned in full for either, with its
reconciliation over both allocations, because that reconciliation is what says how much is
still allocatable or refundable. To show one document, select the allocations with
`financialDocumentReference.id == documentId`. A payment that was never allocated cannot
be discovered from a document; `unappliedPayments()` discovers it while kept value remains.
Its `reconciliation.unallocated` is `payment amount - total refunds - effective net
allocations`. Refund allocations unwind applied money but do not make refunded money
available again. A payment with positive `unallocated` may be fully or partly unapplied;
the full payment record contains amount, currency, method, receipt time, and any external
reference. The list is unpaged and ordered deterministically; applications may expose a
UI-specific pagination contract later if needed.

The convenience overloads run in one `REPEATABLE_READ` transaction, so a result never mixes
committed states, and they take no row lock, so a history read neither waits for nor
delays an allocation or refund. The `Transaction` overloads join the caller's transaction
and see its own uncommitted writes; they add no isolation of their own, so an application
that needs a coherent history from an outer transaction opens it with
`TransactionIsolation.REPEATABLE_READ`. Code already inside an application-owned transaction
calls the `Transaction` overload: the convenience overload owns its transaction, and on the
same thread it would join the open one rather than change its isolation. To act on a history (allocate or refund), use the
mutation, which locks and validates against the payment's committed facts.

```kotlin
// After a reload: which payments and allocations exist for this invoice?
val histories = context.financialLedger.paymentHistoriesForLineage(invoiceId)
val history = histories.single { it.payment.id == paymentId }
val allocation = history.allocations.first { it.financialDocumentReference.id == invoiceId }
context.financialLedger.recordRefund(
    paymentId, refundId, amount, method, refundedAt, null,
    listOf(RefundAllocationPortion(refundAllocationId, allocation.id, amount, refundedAt)),
)
```

An application creates authoritative `LineItem`s and a domain document itself, then can
join its relationship write to the financial write:

```kotlin
context.transactor.inTransaction { transaction ->
    context.financialLedger.create(transaction, estimate)
    applicationRepository.associate(transaction, inquiryId, estimate.id)
}
```

The application owns its identifiers, pricing, relationships, HTTP routes, authentication,
and policy on which stages accept payments and which refunds are allowed. No financial,
payment, or refund routes are mounted by the runtime in this slice. Existing built-in commerce permission keys remain available
for an application that exposes its own protected routes.

### Deposit requirements and financial lineage reads

`FinancialLedger` provides immutable, approved deposit terms for financial-document
lineages. It imposes no stage eligibility or application workflow policy. The domain's
`DepositTerms.Fixed(Money)` and `DepositTerms.Percentage(BigDecimal)` resolve against the
exact approved snapshot; the resolved amount is frozen forever. Later document versions
neither recalculate nor withdraw terms. Active satisfaction is derived on read from
`FinancialDocumentReconciliation.netApplied >= requiredAmount`; refunds can undo it.

| Ledger API | Contract |
|---|---|
| `activateDepositRequirement(documentId, expectedDocumentVersion, terms, expectedRequirementRevision)` | Append Active: first approval, replacement, or reactivation. Both expected tokens must match. Null expects no requirement history, including no withdrawal history. |
| `withdrawDepositRequirement(documentId, expectedRequirementRevision)` | Append Withdrawn after current Active. Requires no expected document version. |
| `latestDepositRequirement(documentId)` | Null means never configured; otherwise latest Active or Withdrawn with its timestamp. |
| `depositRequirementHistory(documentId)` | Complete immutable history, oldest first, empty for never configured. |
| `financialLineages(documentIds)` | Current coherent financial views of explicit lineages; also suitable for a single lineage. |

Every method also takes a caller-owned `Transaction` as its first argument. Mutations
return `DepositRequirementVersion(requirement, createdAt)`, a closed read model with a
database-assigned timestamp. Stale expected tokens return `CommerceFailure.Conflict`;
missing lineages or withdrawal without history return `NotFound`; an already withdrawn
requirement returns `IllegalTransition`; invalid approval amounts return `ValidationFailed`.
Term construction follows the domain's `require` convention and can be wrapped with
`validating` when translating caller inputs.

```kotlin
val approval = context.transactor.inTransaction { transaction ->
    context.financialLedger.activateDepositRequirement(
        transaction,
        document.id,
        document.version,
        DepositTerms.Percentage(BigDecimal("25")),
        expectedRequirementRevision = null,
    )
    // Application-owned relationship writes may use this same transaction.
}
val view = context.financialLedger.financialLineages(listOf(document.id)).single()
val satisfied: Boolean? = view.depositSatisfied
```

V13 creates `commerce.deposit_requirement_revisions`, identified by `(document_id,
revision)`, with immediate predecessor links, one successor per predecessor, an exact
approval-snapshot foreign key, checked Active/Withdrawn forms, and `created_at` assigned by
`clock_timestamp()`. Withdrawn contains no terms. Exact decimal values and their original
scales preserve both original terms and frozen money, including negative BigDecimal
scales. Restoration validates the original approval snapshot and rejects malformed data
with `IllegalStateException`. A database trigger rejects UPDATE/DELETE of revisions.
The migration is additive: existing documents retain their timestamps and payment facts,
and receive no invented requirement history.

V13 also creates `commerce.financial_document_lineages`, holding only each document ID
and its current snapshot reference. Snapshot-insert triggers maintain that reference,
including inserts from older runtimes, and take the lineage lock before inserting a
successor. It is internal concurrency infrastructure, not another financial fact or a
balance. Requirements lock this row with `FOR NO KEY UPDATE` before checking both tokens
and inserting. This protects first approval as well as replacement; competing writers
receive Conflict. The reference changes when documents advance, so PostgreSQL also
rejects a stale `REPEATABLE_READ` mutation as Conflict; retry the whole caller transaction.
Document snapshots and requirement revisions remain immutable. These operations take
no payment locks, and allocation/refund operations take no lineage locks: payment lock
order is preserved. Reads take no mutation locks.

`financialLineages(ids)` returns an unmodifiable list of `FinancialLineageView` in the
input's iteration order. Empty input returns empty output; duplicates fail
`ValidationFailed`; any missing ID fails `NotFound`, without omitting results. Each view
contains `latestVersion`, `reconciliation`, nullable `depositRequirement`, nullable
`depositSatisfied` (only Active has satisfaction), and `activity`. Four set-based queries
read all requested lineages; no per-lineage queries or transactions are opened.

`FinancialLineageActivity` reports objective facts:

| Property | Event |
|---|---|
| `latestDocumentVersionAt` | Latest financial-document version's database creation instant. |
| `latestDepositRequirementAt` | Latest requirement revision's database creation instant, including withdrawal. |
| `latestPaymentAllocationAt` | Maximum allocation `allocatedAt` across every version of the lineage. |
| `latestRefundAllocationAt` | Maximum matching refund-allocation `allocatedAt` that unwound a lineage allocation. |
| `latestFinancialActivityAt` | Exactly the maximum of these available timestamps. |

Unapplied payments, payment receipt times (including backdated receipts), standalone
refunds, and unrelated refunds contribute nothing. Fully refunded allocations retain
their historical allocation activity. Applications decide how to use these facts.

Requirement convenience reads and the whole bulk read use one `REPEATABLE_READ`
transaction. Their transaction-taking overloads use only the caller's transaction and
never change its isolation. Choose `REPEATABLE_READ` at the outer boundary for coherent
concurrent multi-query reads; `READ_COMMITTED` can combine different committed snapshots.
Inside an application-owned transaction, use the transaction-taking overload rather than
nesting a convenience method with a conflicting isolation request.

Hosts conventionally gate deposit reads with `CommercePermissions.FinancialDocumentRead`
and mutations with `CommercePermissions.DepositRequirementManage`
(`commerce.deposit-requirement.manage`), described in the financial-documents permission
group. Financial-document creation grants no authority to modify deposits. This package
adds no deposit HTTP routes; hosts supply their own authorization and transport.

### Transactions

`Transactor.inTransaction { transaction -> ... }` commits when the block returns and rolls
back when it throws, rethrowing the original exception. An operation that coordinates
several persistent concepts does everything inside one `inTransaction` call, opens it once,
and passes the caller-owned `Transaction` to repositories and services. For example:
payment recorded, allocation recorded, reconciliation derived, booking policy evaluated,
booking and document transitioned. Repository methods take the `Transaction` as their
first parameter and all of them participate in that one transaction.

#### Transaction isolation

```kotlin
transactor.inTransaction { transaction -> ... }                                       // runtime default
transactor.inTransaction(TransactionIsolation.READ_COMMITTED) { transaction -> ... }  // explicit READ COMMITTED
transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction -> ... } // explicit REPEATABLE READ
```

```kotlin
val result =
    transactor.inTransaction(
        isolation = TransactionIsolation.REPEATABLE_READ,
    ) { transaction ->
        // Multiple SELECTs in this block observe one PostgreSQL snapshot.
        repositoryA.find(transaction, ...)
        repositoryB.find(transaction, ...)
    }
```

- Without an isolation, the transaction uses the runtime's default: the pool baseline,
  which `createDataSource` sets to `READ COMMITTED`. Passing a `TransactionIsolation`
  requests exactly that level, whatever the connection's baseline, so
  `REPEATABLE_READ` must always be requested explicitly.
- Isolation applies to the whole transaction and is in effect before its first statement.
  The runtime owns the translation to JDBC and PostgreSQL; `TransactionIsolation` is the
  only type callers use.
- Choose isolation at the outer transaction boundary. Repositories and operations that
  receive the `Transaction` inherit it through the caller-owned `Transaction`; they take no
  isolation parameter and never open a transaction of their own.
- The level is scoped to that one call. When a connection returns to the pool it is back
  at its baseline: JDBI restores the previous level when the transaction ends, and
  `createDataSource` sets the `READ COMMITTED` baseline that Hikari also restores.
- Readers at `REPEATABLE_READ` do not block writers. It gives coherent multi-query reads;
  it does not serialize writes. Explicit row locking (`SELECT ... FOR UPDATE`) and
  expected-version checks remain the tools for that. Other levels, and `SERIALIZABLE`
  retry handling, are not supported.

#### Nested `inTransaction`

Prefer passing the `Transaction` down; it makes transaction ownership and isolation
explicit. Calling `inTransaction` again on the same thread from inside a block does not open
a separate transaction. JDBI joins the managed handle, so the inner block uses the same
connection and commits or rolls back with the outer transaction, and an outer failure rolls
back the inner block's writes too. An inner call without an isolation inherits the outer
transaction's, and one that repeats it is accepted. An inner call requesting a different
isolation fails with JDBI's `TransactionException`, because the level of an open
transaction cannot change; the outer transaction rolls back if that exception escapes its
block. Only the outer call chooses the isolation.

### HTTP and errors

Bodies are JSON (`CommerceJson`): unknown request fields are ignored, defaults are
written, and `null` optionals are omitted. Transport DTOs are `@Serializable` classes in
the runtime. Routes translate between DTOs and domain values explicitly.

Every error has one shape:

```json
{"code": "validation_failed", "message": "Financial document 5f0c6a7e-... must contain at least one line item"}
```

For structured validation results, an optional `violations` array carries stable codes:

```json
{"code":"validation_failed","message":"Selection cannot be priced","violations":[{"code":"TOO_MANY_SELECTIONS"}]}
```

`offeringsValidationFailed(message, violations)` preserves codes from structural and
application-defined `OfferingsViolation` values. The message stays diagnostic; the domain
violation interface does not provide a reliable field path or per-violation message, so
neither is fabricated. Errors without structured violations retain the two-field JSON
body. The Offerings OpenAPI contract uses `ValidationErrorResponse` to describe the
optional array and leaves the base `ErrorResponse` schema unchanged.

| Category | Status | `code` | Raised by |
|---|---|---|---|
| Malformed request | 400 | `malformed_request` | unreadable JSON, missing fields, unparsable path values (http4k `LensFailure`) |
| Validation failure | 422 | `validation_failed` | `CommerceFailure.ValidationFailed`, typically a domain `require` inside `validating { }` |
| Not found | 404 | `not_found` | `CommerceFailure.NotFound`, or no matching route |
| Unauthenticated | 401 | `unauthenticated` | a missing or invalid session in `sessionAuthentication`, `requirePermission` without an authenticated principal, or reading `authenticatedPrincipal` on an unauthenticated request |
| Forbidden | 403 | `forbidden` | `requirePermission` when the authenticated principal lacks the permission |
| Conflict | 409 | `conflict` | `CommerceFailure.Conflict`, e.g. a unique-key violation |
| Illegal transition | 409 | `illegal_transition` | `CommerceFailure.IllegalTransition` |
| Invariant violation | 422 | `invariant_violated` | `CommerceFailure.InvariantViolated` |
| Internal failure | 500 | `internal_failure` | anything else; logged, never described |

Messages come only from `CommerceFailure`, whose messages are written for callers. SQL
text, stack traces, and exception details of unexpected failures never reach a response.
`CommerceErrorHandling` also normalizes the http4k contract router's own parameter
failure response to `malformed_request`, preserving this shape for mounted contract routes.

### Sessions and authorization

The runtime manages what happens **after** an application has proven who a human caller
is. It never sees human credentials. Services are the exception: the runtime authenticates
them itself; see [Service authentication](#service-authentication).

```text
commerce-domain        Principal, UserId, ServiceId, roles, permissions,
                       PermissionResolver, PrincipalId.can

commerce-runtime       authenticated-session lifecycle (SessionManager)
                       session persistence (commerce.principal_sessions)
                       principal and RBAC persistence (commerce authorization tables)
                       live PrincipalResolver, RoleResolver, PermissionResolver
                       administration operations and HTTP capability
                       token issuance and resolution
                       HTTP principal context (authenticatedPrincipal)
                       permission enforcement (requirePermission, AccessControl)

concrete application   credential storage and verification
                       login and logout endpoints
                       OAuth, passkeys, or any other identity proof
                       bootstrap policy and application permission definitions
                       vertical staff profiles
                       how the token reaches its client, and CSRF protection
```

Logging in, in the concrete application:

```kotlin
val userId = applicationAuthenticator.authenticate(credentials) // the application's own check
    ?: return Response(Status.UNAUTHORIZED)

val issued = context.sessions.create(userId)

// The application chooses how issued.token reaches its client, for example:
Response(Status.NO_CONTENT).cookie(sessionCookie.issue(issued))
```

Later requests:

```text
HTTP request
    → SessionTokenExtractor          (BearerSessionToken, SessionCookie, or the application's own)
    → SessionManager.resolve(token)  (digest lookup plus current principal status; invalid → 401)
    → authenticatedPrincipal         (http4k request context)
    → requirePermission(permission)  (PrincipalId.can with the live runtime PermissionResolver → 403)
    → business handler
```

```kotlin
val access = AccessControl(sessionAuthentication(context.sessions, sessionCookie), context.authorization)

routes(
    "/auth/login" bind Method.POST to access.public().then(login),
    "/auth/logout" bind Method.POST to access.authenticated().then(logout),
    "/bookings" bind Method.GET to access.requirePermission(CommercePermissions.BookingRead).then(listBookings),
)

val listBookings: HttpHandler = { request ->
    val principal: PrincipalId = authenticatedPrincipal(request)
    // ...
}
```

**Authentication precedence follows composition order.** Once a runtime authentication
filter establishes `authenticatedPrincipal` on a request, nested `AccessControl` instances
reuse that principal rather than authenticating the request again. An `AccessControl`'s
own authentication filter is a fallback, used only when no principal is established yet.
For example:

```text
outer sessionAuthentication(cookie)  → establishes UserId
nested AccessControl (bearer)        → reuses UserId; bearer credentials are not inspected
requirePermission                    → evaluates UserId against the current PermissionResolver
```

If a request carries credentials for two different principals (a session cookie for one
and a bearer token for another), the filter responsible for establishing the principal
wins, nested `AccessControl` never replaces it, and authorization evaluates that
principal. The runtime does not parse every credential source or try to reconcile them.
`sessionAuthentication` is idempotent for a request that already has a runtime-authenticated
principal. The first runtime authentication filter to establish the principal wins; nested
session-authentication filters reuse it without inspecting their own transport, so they
neither replace it nor answer `401` because their transport is absent.

Authentication transport is not authorization. If an endpoint ever needs to require a
particular authentication mechanism or assurance level, that will be modeled explicitly;
it must not rely on the order in which authentication filters are nested.

Authentication may be reused; authorization never is. The permission is evaluated on every
request. Only runtime authentication filters can establish the principal, so no header,
query parameter, request attribute, or application flag can.

Handlers never parse cookies, hash tokens, query sessions, check expiry, or re-check
permissions. **A session establishes identity, not authority.** Permissions are never
captured in a session: every `requirePermission` decision asks the current
`PermissionResolver`, so a changed role assignment or a disabled principal takes effect
on sessions that are already active.

| API (`runtime.session` unless noted) | Purpose |
|---|---|
| `SessionManager` (`context.sessions`) | `create(principalId)`, `resolve(token)`, `revoke(token)`, `revokeAll(principalId)`. Each also has an overload that joins the caller's `Transaction`, so, for example, disabling a principal and revoking its sessions commit together. |
| `PrincipalSession`, `SessionId` | One session of one `PrincipalId`: creation, expiry, and optional revocation times; `isActive(at)`. No credential material. |
| `SessionToken` | The opaque secret. `parse(text)` accepts only the runtime's format; `toString()` is redacted and equality is constant-time. |
| `IssuedSession` | The result of `create`: the session and its token. The only place a raw token is handed out. |
| `SessionTokenExtractor`, `BearerSessionToken`, `SessionCookie` | Where a request carries its token. Transport only; they decide nothing about validity. |
| `sessionAuthentication(sessions, extractor)` | The filter that resolves the token and sets `authenticatedPrincipal`, or answers `401`. |
| `runtime.http`: `authenticatedPrincipal` | The read-only request lens handlers use. Only the runtime's authentication filters set it. |
| `runtime.http`: `requireAuthenticatedPrincipal`, `AccessControl` | Authorization filters and the declarative `public()` / `authenticated()` / `requirePermission(...)` route protection, bound to the running permission catalog. |

**Sessions authenticate only known, ACTIVE runtime principals.** `create` checks the
runtime directory in its transaction: an unknown principal raises `NotFound`, and a
disabled one raises `Conflict`. `resolve` checks current principal existence and status
alongside session state, returning `null` if the principal is missing or disabled. The
session manager and authorization directory share an internal repository; neither
depends on the other to resolve principal status. Disabling still atomically revokes
existing sessions. Re-enabling permits a new session but never revives a revoked token.

Security decisions:

- **Tokens.** 32 bytes from `SecureRandom`, unpadded base64url (43 characters). A token is
  unrelated to its `SessionId` and to the principal, and neither identifier can be parsed
  as a token.
- **Storage.** Only the SHA-256 digest of the token is stored, in a unique column. The
  token is uniformly random, so a fast digest is sufficient and password hashing (bcrypt,
  Argon2, PBKDF2) would add nothing. The digest type and the repository are internal, so
  no public API accepts or returns stored token material. After the indexed lookup, the
  digest is also compared in constant time.
- **Expiry.** Fixed, never sliding: a session expires `sessions.lifetimeMinutes` after it
  was created, and resolving it never extends it. Every timestamp comes from the runtime's
  clock, never the database clock.
- **Revocation** is immediate and idempotent. `revokeAll` revokes only that principal's
  active sessions; a user and a service that share a UUID are different principals.
- **Failures.** A missing, malformed, unknown, expired, or revoked token all produce the
  same `401 unauthenticated` response. Tokens never appear in logs, `toString`, or
  exception messages. Lifecycle logs (`event=session_created`, `event=session_revoked`,
  `event=sessions_revoked`) name session and principal identifiers only.
- **Fail closed.** `requirePermission` answers `401` without an authenticated principal,
  and reading `authenticatedPrincipal` on a request that was not authenticated is reported
  as `401`, not as a malformed request. `AccessControl` re-checks the principal after its
  authentication filter.
- **Cookies are an optional adapter.** `SessionCookie(name)` reads the token from a cookie
  and builds cookies that are always `Secure` and `HttpOnly`, host-only (no `Domain`),
  `SameSite=Lax` by default, and expire with the session. The application names the
  cookie (the `__Host-` prefix is recommended) and decides when to set and clear it. CSRF
  protection beyond `SameSite` is the application's.

**Runtime capabilities declare permissions; applications supply `AccessControl`.** A
runtime-provided HTTP capability that exposes protected operations declares the commerce
permission each one requires and receives the application's `AccessControl` explicitly
when composed. The application chooses its authentication transport and builds
`AccessControl(authentication, context.authorization)`, which binds the permission catalog,
the live effective permissions, and principal identity from the one running authorization
directory; they cannot be supplied from unrelated sources. `commerceRuntime(...)` takes no
global resolver: public capabilities need none, and a protected capability cannot be
composed without `AccessControl`. Evaluation always goes through `PrincipalId.can`, and
nothing about missing authorization configuration ever means "allow".

**Every enforced permission is in the catalog.** `AccessControl.requirePermission(key)`
rejects a key the running `PermissionCatalog` does not define when the route is composed,
with `IllegalArgumentException: Permission is not registered in the running
PermissionCatalog: <key>`. A typo such as `commerce.principal.reed` therefore fails
`commerceRuntime(...)` instead of producing a route nobody can ever be granted. Effective
permissions are checked against the catalog on every resolution: a key outside it fails
closed with an internal error (`500 internal_failure`, logged), is never filtered out, and
never authorizes. The catalog is fixed for the life of the process; grants and effective
permissions stay live.

**Principal persistence contract.** commerce-runtime persists an explicit set of supported
`PrincipalId` representations: today `UserId` as `USER` and `ServiceId` as `SERVICE`, each
with its UUID. Adding a `PrincipalId` subtype to commerce-domain does not make it
session-persistable. Runtime support must deliberately add the encoding and decoding
mapping (`PrincipalIdColumns`, whose exhaustive `when` over the sealed `PrincipalId` stops
compiling until it does) and a runtime migration that widens the `principal_kind` check
constraint. This is intentional, so authentication identities fail closed rather than being
serialized implicitly: there is no class-name, `toString`, or generic serialization, and no
registry of principal kinds.

### Authorization directory and administration

| State | Owner |
|---|---|
| Human authentication credentials (password hashes, OAuth identities, passkeys) | Concrete application |
| Service credentials and service access tokens | commerce-runtime |
| Authenticated session | commerce-runtime |
| Principal and RBAC state | commerce-runtime |
| Vertical profile data (phone, payroll, store assignment) | Concrete application |

`context.authorization` provides `principalResolver`, `roleResolver`, the live
`permissionResolver`, the read-only `permissionCatalog`, and principal/role administration
methods. It persists human `User` and `ServiceIdentity` values and their statuses, role
definitions and permission sets, and principal-role assignments. `RoleBasedPermissionResolver`
combines the live resolvers. Missing or disabled principals grant nothing. Assigning or
removing a role, or replacing a role's permissions, affects the next request without a
new session. Disabling either principal kind changes its status and calls
`sessions.revokeAll` in the same transaction, so authenticated-only routes also reject
old tokens. Re-enabling does not restore revoked sessions.

Usernames are trimmed on storage and lookup and normalized for uniqueness as ASCII
lowercase with `Locale.ROOT`: `" Brayan "` and `"BRAYAN"` both identify `brayan`. Allowed
characters are ASCII letters, digits, period, underscore, and hyphen. The display form
is stored separately. A duplicate normalized name is `409 conflict`.

Built-in permission definitions are listed explicitly in
`commercePermissionDefinitions`. An application contributes its own code-backed keys via
`ApplicationContributions(permissionDefinitions = listOf(...))`; see
[Permission catalog and effective permissions](#permission-catalog-and-effective-permissions).
Duplicate keys fail runtime composition; unknown or malformed keys fail role creation or
permission replacement with `422 validation_failed`, naming the unknown keys. A repeated
key in one request is one grant. Permissions cannot be created by an administration route.
After runtime and application migrations, before HTTP composition, the runtime rejects
any persisted `commerce.role_permissions` key missing from the running catalog and names
the unknown keys. An application release removing a permission must migrate away its
stored grants first. Live resolution checks the same catalog again and fails closed if
an unknown grant appears after startup; the startup failure is never silently filtered.
Role administration reads go through the same check: every `RoleDefinition` the directory
returns (`getRole`, `listRoles`, the live `roleResolver`, and the results of role
mutations) holds only catalog permissions. Raw stored values are checked before any
becomes a `PermissionKey`, so an unknown (`mystery.permission`) or malformed
(`Legacy.Key`) grant written after startup fails the read closed with the stored-grant
diagnostic naming it; over HTTP that is `500 internal_failure`, and the value is not in the
response. One bad grant fails `listRoles` entirely rather than omitting a role or a grant.
Changing such a role's details or assigning it fails the same way.
`replaceRolePermissions` does not read the previous grants, so it is how an administrator
replaces stale ones; deleting an unassigned role also does not read them.
`CommercePermissions.RoleRead` inspects roles and the catalog; `RoleManage` changes role
definitions and grants; `RoleAssign` changes assignments. `PrincipalRead` and `PrincipalManage`
(`commerce.principal.read` and `commerce.principal.manage`) cover both human and service
identities. `RoleAssign` can attach any existing role to a principal; it has no grant
ceiling in this version. All conventional role keys, including `Administrator`,
have only the grants explicitly stored by the application. There is no bypass or wildcard.

The administration methods have overloads taking the caller's `Transaction`. This lets
application credential rows and runtime principal/role rows commit or roll back together.
Creation requires an empty initial role set; use `assignRole` afterward. Assignment is
idempotent and removal of an absent assignment is a no-op. Deleting an assigned role
returns `409 conflict`; it never silently removes grants from principals. Principals
are disabled rather than deleted.

```kotlin
// Bootstrap policy and the password hash come from this application.
context.transactor.inTransaction { transaction ->
    val role = RoleDefinition(CommerceRoles.Administrator, "Administrator", null,
        setOf(CommercePermissions.PrincipalRead, CommercePermissions.PrincipalManage,
            CommercePermissions.RoleRead, CommercePermissions.RoleManage,
            CommercePermissions.RoleAssign))
    context.authorization.createRole(transaction, role)
    val administrator = context.authorization.createUser(transaction,
        User(UserId(UUID.randomUUID()), "admin", null, null, "Administrator",
            PrincipalStatus.ACTIVE, emptySet()))
    applicationCredentials.insertPasswordHash(transaction, administrator.id, argon2Hash)
    context.authorization.assignRole(transaction, administrator.id, role.key)
}
```

The host mounts the opt-in contract routes and its own session transport:

```kotlin
val access = AccessControl(
    sessionAuthentication(context.sessions, appSessionCookie),
    context.authorization,
)
val admin = authorizationAdministrationHttpCapability(
    context = context,
    accessControl = access,
    basePath = "/admin/access",
    tags = setOf(Tag("Staff administration")), // optional OpenAPI grouping
)
val api = contract {
    renderer = OpenApi3(ApiInfo("My application", "1"), Jackson)
    descriptionPath = "/openapi.json"
    routes += admin.contractRoutes
}
```

As with Offerings, the optional `tags` are the host's OpenAPI grouping and apply to every
administration route. Blank names are rejected.

At that base path, the capability exposes:

| Method | Path | Required permission |
|---|---|---|
| GET, POST | `/users` | `PrincipalRead`, `PrincipalManage` respectively |
| GET, PATCH | `/users/{userId}` | `PrincipalRead`, `PrincipalManage` |
| PUT | `/users/{userId}/status` | `PrincipalManage` |
| GET | `/users/{userId}/roles` | `PrincipalRead` |
| PUT, DELETE | `/users/{userId}/roles/{roleKey}` | `RoleAssign` |
| GET, POST | `/services` | `PrincipalRead`, `PrincipalManage` respectively |
| GET, PATCH | `/services/{serviceId}` | `PrincipalRead`, `PrincipalManage` |
| PUT | `/services/{serviceId}/status` | `PrincipalManage` |
| GET | `/services/{serviceId}/roles` | `PrincipalRead` |
| PUT, DELETE | `/services/{serviceId}/roles/{roleKey}` | `RoleAssign` |
| GET, POST | `/roles` | `RoleRead`, `RoleManage` respectively |
| GET, PATCH, DELETE | `/roles/{roleKey}` | `RoleRead`, `RoleManage`, `RoleManage` |
| PUT | `/roles/{roleKey}/permissions` | `RoleManage` |
| GET | `/permissions` | `RoleRead` (the [permission catalog](#permission-catalog-and-effective-permissions) route) |

The runtime handles `401` for no valid session and `403` for a principal lacking the
required permission before reading mutation bodies. OpenAPI lists these responses and
the standard commerce errors. The host owns the aggregate OpenAPI document and Swagger
UI. This capability has no login, credentials, cookies, or default administrator.

### Permission catalog and effective permissions

Four questions stay separate:

| Question | Answered by |
|---|---|
| Which permissions exist? | `PermissionCatalog` (`context.authorization.permissionCatalog`) |
| Which permissions does principal X hold now? | `PermissionResolver` (`context.authorization.permissionResolver`), always a subset of the catalog |
| May principal X perform operation Y? | `AccessControl.requirePermission` / `PrincipalId.can`, on every request |
| Which known permissions does role R grant? | Role administration (`AuthorizationDirectory`) |

**The catalog** is the running application's whole authorization vocabulary: the runtime's
`commercePermissionDefinitions` plus the application's
`ApplicationContributions.permissionDefinitions`, composed once by `commerceRuntime(...)`.
Each entry is a commerce-domain `PermissionDefinition`: the canonical `PermissionKey`
that role grants and enforcement use, a `PermissionGroup` for presentation, a display
name, and a description, all required. Keys and groups are lowercase dot-separated
segments (`users.read`, `catering.inquiries.assign`); malformed values are rejected, never
normalized. The catalog is immutable, ordered by key whatever the registration order, and
rejects a key contributed twice by naming it, before the runtime starts. It is code-defined
and never persisted. Its `revision` (`sha256:` plus a hex digest of the canonical contents)
changes whenever a definition is added, removed, or changed, and never otherwise; it is
for diagnostics and frontend compatibility checks, not authorization.

The catalog is the authority for "is this key known to this application?": route
declarations (`AccessControl.requirePermission`) reject unknown keys at composition, role
creation and grant replacement reject them at mutation, and startup and live resolution
fail closed on stored grants it does not define, naming the stored value even when it is
not a well-formed key (`Legacy.Key`). Effective permissions outside the catalog fail
closed. It is never the authority for whether a principal holds a permission, and knowing
a key confers nothing.

Every permission a runtime capability enforces must be defined in
`commercePermissionDefinitions`, including runtime infrastructure permissions that are not
`CommercePermissions`. Composing a capability that declares an undefined key fails, and
`AuthorizationReadHttpSpec` composes every runtime capability against a runtime-only
catalog to keep it so.

The runtime describes every key in `CommercePermissions` and `RuntimePermissions`. Its own
routes enforce `OfferingsManage`, `PrincipalRead`, `PrincipalManage`, `RoleRead`,
`RoleManage`, `RoleAssign`, and `RuntimePermissions.ServiceCredentialManage`; the booking, financial-document, payment, and refund keys are conventional
names for operations an application enforces. `DepositRequirementManage`
(`commerce.deposit-requirement.manage`) conventionally gates deposit mutations, and
`FinancialDocumentRead` gates their reads; both appear in the financial-documents group.

An application contributes its permissions and chooses which routes to mount:

```kotlin
object CateringPermissions {
    private val inquiries = PermissionGroup("catering.inquiries")
    val AssignInquiry = PermissionKey("catering.inquiries.assign")
    val definitions = listOf(
        PermissionDefinition(AssignInquiry, "Assign inquiries", "Assign inquiries to staff.", inquiries),
    )
}

val runtime = commerceRuntime(configuration, ApplicationContributions(
    permissionDefinitions = CateringPermissions.definitions,
    routes = { context ->
        val access = AccessControl(sessionAuthentication(context.sessions, appSessionCookie), context.authorization)
        val tags = setOf(Tag("Access"))
        listOf(contract {
            renderer = OpenApi3(ApiInfo("Catering", "1"), Jackson)
            descriptionPath = "/openapi.json"
            routes += permissionCatalogHttpCapability(access, "/authorization", tags).contractRoutes
            routes += currentPrincipalHttpCapability(access, "/me", tags).contractRoutes
            // Enforcement uses the same key the catalog describes.
            routes += "/inquiries" / Path.of("id") / "assignee" bindContract PUT to { id: String, _: String ->
                access.requirePermission(CateringPermissions.AssignInquiry).then(assignInquiry(id))
            }
        })
    },
))
```

`PermissionCatalog.of(commercePermissionDefinitions, CateringPermissions.definitions)`
builds the same catalog outside the runtime, for example in a unit test.

Neither route exists unless the host mounts it. Both take only the `AccessControl`, and
serve the catalog, identity, and effective permissions of the authorization it enforces,
so they cannot describe different authorization state than the one protecting the routes.
`authorizationAdministrationHttpCapability` likewise rejects an `AccessControl` built from
another directory.

`permissionCatalogHttpCapability` serves `GET {basePath}/permissions` and requires
`RoleRead`, which reads roles and the vocabulary without the right to change them
(`RoleManage`). `authorizationAdministrationHttpCapability` includes the same route
implementation, with the same representation and protection, so mount one or the other at
a base path:

```json
{
  "revision": "sha256:3f5c...",
  "permissions": [
    {
      "key": "catering.inquiries.assign",
      "group": "catering.inquiries",
      "displayName": "Assign inquiries",
      "description": "Assign inquiries to staff."
    }
  ]
}
```

`RoleRead` stays the catalog permission deliberately. Permission keys are not secrets, so
the only reason for a narrower permission would be a principal that needs the full
vocabulary without seeing role definitions. Building or checking a role-management UI
needs both anyway, and a client that only wants to detect a vocabulary change compares
`permissionCatalogRevision` from the current principal route, which needs no permission.

`currentPrincipalHttpCapability` serves `GET {path}` to any authenticated principal (`401`
otherwise) and returns its identity and its **effective** permissions, resolved on every
request through the `AccessControl`, the same resolution that enforces every
`requirePermission`. A principal without grants gets `[]`. Role keys are deliberately
absent, so clients never derive authority from role names:

```json
{
  "principal": { "kind": "USER", "id": "0b6f...", "displayName": "Staff Member" },
  "permissions": ["catering.inquiries.assign", "commerce.role.read"],
  "permissionCatalogRevision": "sha256:3f5c..."
}
```

`kind` is `USER` or `SERVICE`; a service's `displayName` is its name. Both routes declare
every field as required and non-null in OpenAPI.

**The current principal is the request's principal, not the human at a screen.** The route
describes whichever principal authenticated the backend request. With a
backend-for-frontend in between:

```text
browser user
    → frontend / BFF
        → commerce backend, authenticated as the BFF's own SERVICE principal
```

a `GET /me` made by the BFF with its own service credential correctly returns the BFF's
service identity and the service's permissions. It does not recover the browser user's
identity, and nothing in the runtime does: there is no delegation, impersonation,
forwarded-user header, or act-as semantics, and a service is not trusted for being one.
A BFF that needs its user's identity or permissions must obtain them through its own,
separately designed means.

**Frontend checks are user experience, not security.** A frontend may hide an "Add user"
control when `permissions` lacks a key, and may compare the keys it references with the
catalog to detect a deployment mismatch. That comparison is the frontend's. The server
still enforces every protected operation's permission on every request.

**Session cleanup.** Expired and revoked rows stay in `commerce.principal_sessions`; they
authenticate nothing. Purging old inactive rows would be a runtime capability (an operation
over the `expires_at` index); when and how often to run it would be application or
deployment policy. The runtime provides neither yet, and it will not schedule background
jobs itself.

Sessions may belong to any `PrincipalId`, human or service, and authorization treats them
alike. Service authentication is nevertheless a separate capability that meets sessions
only at `PrincipalId`; see [Service authentication](#service-authentication).

`/health` and `/ready` stay explicitly public and never authenticate. Application routes are
protected only where the application applies these filters: the runtime does not
authenticate globally.

### Service authentication

A SERVICE principal, such as a backend-for-frontend, authenticates with a long-lived
credential and receives a short-lived bearer access token. The token then enters the same
authorization as a user's session:

```text
service credential (service id + secret)          proves identity
        │  POST <token path>
        ▼
short-lived access token (HS256 JWT)              proves recent authentication
        │  Authorization: Bearer <token>
        ▼
ServiceAccessTokenAuthenticator → ServiceId       the authenticated principal
        ▼
current roles → current permissions → AccessControl
```

**Credentials.** `context.serviceCredentials` creates, lists, and revokes credentials of
service identities in the directory, with `Transaction` overloads like the other runtime
operations. Only services hold them. A credential has a stable, non-secret ID, a label, a
creation time, and a revocation time once revoked. Its secret,
`<credential id>.<256 random bits as base64url>`, is returned only when it is created; the
runtime stores an Argon2id hash of it (m=19 MiB, t=2, p=1, a fresh salt, PHC format) and
never the secret. The ID in the secret selects the one stored hash to check, so
authentication costs exactly one Argon2id verification whatever the service holds, and the
same work is done when the credential is unknown.

**Rotation.** A service may hold several active credentials. Create credential B, deploy
the consumer with it and verify, then revoke credential A. Revocation is permanent and
recorded; disabling a service leaves its credentials in place.

**Tokens.** `context.serviceAccessTokens` (present when `serviceTokens` is configured)
exchanges a service ID and secret for a token, and resolves tokens on later requests. A
token is an HS256 JWS with JOSE type `commerce-service-access+jwt` and only the claims
`iss` and `aud` (the configured issuer), `sub` (the service UUID), `principal_kind`
(`SERVICE`), `iat`, `exp`, and `jti`. It carries no secret, role, or permission. Only HS256
tokens of that type are accepted (never `alg: none` or another algorithm), every claim is
required, issuer, audience, and kind must match exactly, and expiry is judged by the
runtime's clock with no skew. Clients treat tokens as opaque.

**Revocation and suspension semantics.**

| Change | Credentials | Already issued, unexpired tokens |
|---|---|---|
| A role or grant is removed | Unaffected | The next request is authorized against the current roles. |
| A credential is revoked | That credential can never obtain a token again (permanent) | Stay valid until they expire (at most `serviceTokens.lifetimeMinutes`) |
| The service is **disabled** | None can obtain a token; none is revoked | Stop authenticating on the next request; none is revoked |
| The service is **activated again** | Every unrevoked credential works again; revoked ones stay revoked | Every token that has not yet expired authenticates again |

`DISABLED` **suspends** a service; it does not revoke anything. (A service's *sessions* are
the exception: disabling a principal revokes them, as for users.) Access-token revocation
therefore rests on two things only: the short token lifetime and suspension. Tokens are not
stored, so there is no per-token revocation or deny-list.

If a service's credentials may be compromised, suspension alone is not enough, because
reactivating it would revive any token the attacker obtained. Instead:

1. disable the service, so no credential or token of it works;
2. revoke the compromised credentials and create replacements;
3. keep the service disabled until `serviceTokens.lifetimeMinutes` has passed since the
   last moment a compromised credential could have obtained a token;
4. activate the service again.

Like any bearer credential, a token can be replayed by whoever holds it until it expires:
send it only over TLS and never log it.

**HTTP.** The application mounts the endpoints and chooses the mechanisms each route
accepts:

```kotlin
val access =
    AccessControl(
        authentication(
            SessionAuthenticator(context.sessions, SessionCookie("__Host-session")),
            ServiceAccessTokenAuthenticator(context.serviceAccessTokens),
        ),
        context.authorization,
    )
contract {
    security = serviceAccessTokenOpenApiSecurity // OpenAPI declaration only
    routes += serviceAuthenticationHttpCapability(context, "/auth/service/token").contractRoutes
    routes += authorizationAdministrationHttpCapability(context, access, "/admin/access").contractRoutes
}
```

`authentication(...)` tries mechanisms in order and the first that proves a principal wins;
none answers `401`. A session token and a service token can share the `Authorization:
Bearer` header because each mechanism only accepts its own format. A service is never
trusted for being a service: without the permission it is `403` like any user. A
backend-for-frontend that calls with its own service token is that service principal
throughout, including on the current principal route, which reports the service, never
the BFF's user (see
[Permission catalog and effective permissions](#permission-catalog-and-effective-permissions)).

| Endpoint | Access | Behavior |
|---|---|---|
| `POST <token path>` `{"serviceId", "secret"}` | public | `200 {"accessToken", "tokenType": "Bearer", "expiresAt", "expiresIn"}`; `401 unauthenticated` with one message for every authentication failure; `400` for malformed input |
| `GET <admin>/services/{serviceId}/credentials` | `commerce.principal.read` | Credential metadata (`credentialId`, `serviceId`, `label`, `createdAt`, `revoked`, `revokedAt`); never secrets or hashes |
| `POST <admin>/services/{serviceId}/credentials` `{"label"}` | `commerce.service-credential.manage` | `201` with the metadata and `secret`, returned only here |
| `DELETE <admin>/services/{serviceId}/credentials/{credentialId}` | `commerce.service-credential.manage` | `204`; idempotent |

The token and credential-creation responses are `Cache-Control: no-store`. The
`commerce.service-credential.manage` permission (`RuntimePermissions.ServiceCredentialManage`)
lets its holder authenticate as any service, so it is separate from
`commerce.principal.manage`. `serviceAccessTokenOpenApiSecurity` renders as the standard
HTTP bearer scheme `serviceAccessToken`; the token route is marked public. It does not
enforce anything: `AccessControl` does.

**Protecting the token endpoint.** The token endpoint is public by design, and every
syntactically valid attempt costs one memory-hard Argon2id verification (about 19 MiB),
including attempts for credentials that do not exist, which are checked against a dummy
hash so timing does not reveal which credentials exist. The runtime has no request rate
limiter. Production deployments must treat the endpoint as sensitive and, depending on
their topology, do one or both of:

- rate-limit it at the edge or reverse proxy;
- expose it only on a private or internal network that its service consumers can reach,
  rather than to the public internet.

**Logging.** Credential creation and revocation and token issuance are logged at INFO.
Authentication failures are DEBUG diagnostics only, with a reason (`unknown_credential`,
`secret_mismatch`, `credential_revoked`, `service_disabled`, ...) and the service and
credential IDs: an anonymous caller cannot drive an INFO log stream, and failures are not an
audit trail. No log ever contains a credential secret or verifier, an access token, or the
signing key.

### Logging

commerce-runtime emits logs through Kotlin Logging on the SLF4J API, as key=value
`event=` messages (for example `event=server_started`, `event=migrations_completed owner=runtime`,
`event=request_failed`). Ownership is split:

- **commerce-runtime** owns its logging calls and the facade it compiles against (Kotlin
  Logging and `slf4j-api`).
- **The concrete application** owns the SLF4J provider (Logback, Log4j 2, or another
  implementation) and the production logging configuration: levels, appenders, format,
  and any environment-driven level overrides.

The published `commerce-runtime` selects no provider and ships no logging configuration.
An application must therefore add a provider itself, for example:

```kotlin
dependencies {
    implementation("io.github.castab:commerce-runtime:<version>")
    runtimeOnly("ch.qos.logback:logback-classic:<version>") // the application's choice
}
```

Without a provider, SLF4J prints a one-time warning and discards every log event. With
Logback but no configuration, Logback's built-in default logs everything at `DEBUG` to
the console, including Jetty, HikariCP, and Hoplite. Either way, the application should
provide both a provider and its configuration. The runtime's own tests use Logback
(`testRuntimeOnly`) with `src/test/resources/logback-test.xml`, and neither is
published.

### Health

- `GET /health`: liveness. Always `200 {"status":"ok"}` while HTTP is served, and
  checks no dependency.
- `GET /ready`: readiness. `200 {"status":"ready"}` when a database connection
  validates, and `503 {"status":"unavailable"}` otherwise.

## Current capabilities

The runtime's built-in routes are infrastructure only, served by every application built
on it:

| Endpoint | Behavior |
|---|---|
| `GET /health`, `GET /ready` | See [Health](#health). |

Every other route comes from the application through `ApplicationContributions`, served
behind the runtime's error handling and able to use its shared `Transactor`. The runtime
exposes no generic CRUD endpoints.

## Booking extension direction

Applications must be able to own strongly typed booking details that are known at
compile time, while reusing the generic booking machinery. Those types are application
concepts, for example a catering application's `CateringBooking` or a detailing
application's `DetailingBooking`, and they are never runtime concepts. The extension seam
is deliberately **not** designed yet. It will be derived from the concrete requirements of
the known consumers. What this module already guarantees:

- No concrete business booking type exists in `commerce-runtime` or `commerce-domain`,
  and none will.
- Booking details will never be modeled as opaque JSON (`type: String, details: JsonObject`).
  JSONB may later be a *persistence representation* of a strongly typed application
  model. That is a separate decision.
- Booking stays optional. `ApplicationContributions` is broader than booking. Nothing
  about financial documents, payments, or their HTTP and persistence requires a booking,
  and a point-of-sale application contributes no booking functionality at all.
- A booking type parameter, if one is introduced, stays confined to booking APIs. It must
  not spread into financial or payment APIs.

Responsibilities this foundation has identified for the future extension:

1. **Detail schema.** Its own tables, contributed through the existing
   `ApplicationContributions.migrations`, keyed by the application's own booking
   identity (commerce-domain defines no booking record or booking ID).
2. **Transactional persistence.** A repository for its details that takes the runtime's
   `Transaction`, so detail writes commit atomically with the booking identity and
   lifecycle writes. `Transaction` already allows this.
3. **Transport.** A `KSerializer` for its request and response details, since the
   runtime's routes are generic over the booking but must stay strongly typed.
4. **Validation.** Translation from transport to its detail type, reported through
   `CommerceFailure.ValidationFailed`.
5. **Phase rehydration.** The domain's lifecycle phases are adopter-implemented
   interfaces whose transitions are adopter-owned. The runtime therefore cannot build
   phase models itself. The extension must turn a stored booking (identity, phase, and
   details) into its phase type, and persist what its transitions return.
6. **Transition policy.** The business authorization of legal edges (for example, whether
   a quote may become booked once a deposit is reconciled). It is evaluated inside the
   same transaction as the facts it depends on.

The booking-to-financial-document association is application-owned, like every relationship
between application entities and commerce facts. The runtime's part is the shared
transaction in which the application writes it.

## Current limitations

- **No human credentials.** The runtime issues, resolves, and revokes sessions and enforces
  permissions on the routes an application protects. It verifies no human credentials, and
  has no user login endpoints, refresh tokens, sliding expiry, or CSRF protection. Expired
  and revoked session rows are kept; there is no purge operation yet (see
  [Session cleanup](#sessions-and-authorization)).
- **Service authentication is first-party only.** No OAuth or OIDC, no external API keys,
  no mTLS, and no delegated identity (a service acting for a user). Revoking a credential
  does not cut short tokens it already obtained, there is one signing key at a time (changing
  it invalidates outstanding tokens), credential last use is not recorded, and there is no
  built-in rate limiter.
- No booking persistence or orchestration yet. Financial-document snapshots, payment
  records, payment allocations, refund records, and refund allocations are persisted and
  orchestrated by the ledger; payment and financial-document reconciliation are derived
  from those facts.
- Payment-allocation-reversal persistence and orchestration are deferred.
- No idempotency keys, outbox or events, Server-Sent Events, or scheduled jobs yet.
- No payment provider integration. Providers (e.g. a future `stripe-adapter`) sit
  behind the provider-neutral contract in `commerce-domain`. This module will never
  depend on a provider SDK.
- No executable or deployable packaging, by design. Concrete applications own `main()`,
  build their own runnable jar (for example with the Shadow plugin) and image. This
  library publishes a plain jar.
- `ApplicationContributions` covers routes, migrations, and permission definitions, and together with
  `CommerceRuntimeContext` it is provisional (see
  [Provisional extension seam](#provisional-extension-seam)). Other contribution points
  will be added when a concrete consumer needs them.
- JDBI (`Handle`) and HikariCP (`HikariDataSource`) types are exposed in the public API.
  This is intentional but may be narrowed later; see
  [Infrastructure types in the public API](#infrastructure-types-in-the-public-api).
- **Capability selection is intentionally deferred.** The runtime currently serves only
  its infrastructure routes, `/health` and `/ready`. When it gains commerce routes,
  applications will not yet be able to choose which commerce capabilities they serve.
  That will be designed only after concrete consumers show which compositions they need.
  There are no capability flags or capability framework until then.

## Tests

```bash
./gradlew :runtime:test
```

The tests are this repository's only executable consumer of the runtime. The specs cover
configuration loading and validation, the error contract, health and readiness, DTO
serialization, every migration from an empty database, the migration contract (internal runtime
discovery, runtime-before-application ordering proven by a real dependency on a stand-in
runtime stream, independent version spaces, idempotent and concurrent migration,
validation, and startup gating on failures, and the application-owned schema and history: a clean
database ends with `commerce` and the application schema each holding its own
`flyway_schema_history`, none in `public`), and commit and rollback of several
application-owned writes sharing one `Transaction`. `CommerceRuntimeSpec` composes the
runtime the way a concrete application does: it supplies explicit
`ApplicationContributions` (an application migration and routes that receive
`CommerceRuntimeContext` and persist an application-owned table through
`context.transactor`), starts Jetty, exercises it over real HTTP and PostgreSQL, including
error handling and rollback, and closes it.

`FinancialLedgerSpec` and `FinancialLedgerRefundSpec` prove document, payment, allocation,
refund, and refund-allocation round trips, the refund examples and every rejection, atomic
refund recording, and that refunds and allocations of one payment are serialized by its
row lock (observed as a blocked PostgreSQL backend). `FinancialLedgerPaymentHistorySpec`
proves the payment history reads: rediscovery of every id and link after a refund,
lineage discovery across versions, fully unwound allocations, split payments returned whole,
deterministic ordering (including equal receipt times), the immutability and closed
construction of `PaymentHistory`, reads inside an uncommitted caller transaction, and the
`REPEATABLE_READ` isolation of the convenience reads. `MigrationLifecycleSpec` also pins
the checksums of released runtime migrations and migrates a released `V5` database
forward.

`OfferingsSnapshotRepositorySpec` proves round trips of one current row per catalog, save
conflicts (duplicate, stale, missing catalog), concurrent successors serialized on the row
lock, retirement and key reservation across saves, price subtype reconstruction, ordering,
the stored shape including retired entries, strict rejection of malformed rows, and atomic
commit and rollback of a catalog with an application-owned row.

The session specs cover the security contract. `SessionTokenSpec` proves tokens come from
the supplied `SecureRandom`, are canonical 256-bit base64url values that identifiers can
never satisfy, and are redacted from every text form; that the digest is SHA-256; and the
active-session rule. `PrincipalSessionRepositorySpec` proves the table and indexes, `UserId`
and `ServiceId` round trips (including a user and a service sharing a UUID), that PostgreSQL
holds only the digest, digest uniqueness, persisted revocation, and commit and rollback
with application rows in one caller transaction. `SessionManagerSpec` drives a clock decades
away from the database clock through fixed expiry, idempotent revocation, `revokeAll`
isolation, the transaction overloads, and token-free logs. `SessionAuthenticationSpec`
covers missing, malformed, unknown, expired, and revoked tokens (`401`), permission granted
and missing (`403`), permissions changing during a session, users and services alike,
fail-closed handlers, and the cookie adapter. `CommerceRuntimeSpec` shows an application
authenticating identity itself, issuing a session through `context.sessions`, and
recovering the principal from the token over real HTTP.

`PermissionCatalogSpec` covers catalog composition, key ordering independent of
registration, lookup, named duplicate rejection, immutability, and a revision that is
stable across registration order and changes with every added, removed, or edited
definition. `AuthorizationReadHttpSpec` proves the catalog and current principal routes
over real HTTP: `401`, `403` (including for `RoleManage` alone), complete metadata in key
order, routes absent unless mounted, live effective permissions with an empty list for a
principal without grants, services and users alike, and OpenAPI schemas whose required,
non-nullable properties are exactly those of the live responses. It also composes every
runtime capability against a runtime-only catalog (the completeness guard), proves that a
typo such as `commerce.principal.reed` fails `commerceRuntime(...)`, that the standalone and
administration catalog routes serve one identical body, that an `AccessControl` from
another directory is rejected, and that an unknown grant stored after startup fails both
enforcement and the current principal route closed. `AccessControlCatalogSpec` proves,
without a database, composition-time rejection of unregistered keys, fail-closed handling
of a resolver that returns a key outside the catalog (for enforcement and for the current
principal), and live per-request resolution.

The service authentication specs cover its security contract. `ServiceCredentialSecretSpec`
pins the secret format, redaction, and the Argon2id PHC hash. `ServiceAccessTokensSpec` proves
hash-only storage (checked in SQL), several active credentials with independent revocation,
that users can hold no credential (API and schema), one uniform failure for every bad
attempt, disabled services, identity-only claims, expiry at the exact boundary, and the
rejection of modified, wrong-key, other-algorithm, unsigned, wrong-type, wrong-issuer,
wrong-audience, wrong-kind, and incomplete tokens, plus secret- and token-free logs with
failures at DEBUG and administrative events at INFO. Its suspension tests pin that a
disabled-then-reactivated service gets back its unexpired tokens and unrevoked credentials,
while a credential revoked during the suspension stays revoked and a token that expired
during it stays expired. The signer is shown to apply exactly the configuration's
validation policy.
`ServiceAuthenticationHttpSpec` composes the runtime with sessions and service tokens on the
same routes and proves that a token grants nothing by itself: the same unexpired token is
`403`, then `200` after a role grant, `403` after its removal, `401` once the service is
disabled, and `200` again once it is reactivated. It also covers the token endpoint's `401`/`400` behavior, cookie login,
permission enforcement, and logout beside service tokens, credential administration
permissions and rotation, startup failure without a signing key, and the OpenAPI bearer
scheme. `CommerceRuntimeStartupSpec` proves that `commerceRuntime(...)` rejects an invalid
configuration constructed in Kotlin (repeated-byte, short, or non-base64 keys, blank or
padded issuers, out-of-range lifetimes, and general settings) before it touches the
database, and `CommerceRuntimeConfigurationSpec` covers the required issuer in every
file and environment combination.

Database specs run against a real PostgreSQL 18 that the build starts through the Docker CLI.
See [Building and testing](../README.md#building-and-testing).
