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
            migrationLocations = listOf("classpath:db/migration"),
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
application contributes the Flyway locations of its own migrations, never the runtime's,
and its own routes. The routes are built from the shared `CommerceRuntimeContext`, which
currently holds the configuration, the `Transactor`, the offerings snapshot repository, and
the `SessionManager` (`context.sessions`). The runtime owns the error handling around every
route.

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
can insert an offering snapshot and write its own catalog or audit row in the same
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
| `runtime` | `commerceRuntime(...)`, `CommerceRuntime`, `ApplicationContributions`, and `CommerceRuntimeContext` (configuration, `Transactor`, offerings snapshot repository, and `sessions`). |
| `runtime.config` | `CommerceRuntimeConfiguration`: HOCON loading, environment overrides, and validation. |
| `runtime.persistence` | `createDataSource` (HikariCP), `MigrationLifecycle` (the migration phase), `Transactor` and `Transaction`, the offerings snapshot repository, the internal principal session repository, and PostgreSQL error helpers. |
| `runtime.session` | `SessionManager`, `PrincipalSession`, `SessionId`, `SessionToken`, `IssuedSession`, token extractors (`BearerSessionToken`, `SessionCookie`), and the `sessionAuthentication` filter. |
| `runtime.operation` | Support for operations (use cases such as issuing an invoice or recording a payment): `CommerceFailure`, the expected failures of operations, and `validating`. |
| `runtime.http` | `CommerceJson`, `jsonBody`, the error contract and `CommerceErrorHandling` filter, health routes, the `authenticatedPrincipal` request lens, and the authorization filters (`requirePermission`, `requireAuthenticatedPrincipal`, `AccessControl`). |

Packages for booking, financial, and payment orchestration will appear when they contain
real code, not before.

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

`sessions.lifetimeMinutes` is the fixed lifetime of every session, between 1 minute and 1
year; see [Sessions and authorization](#sessions-and-authorization). It is the only session
setting: cookie names and attributes are chosen in code by the application.

Invalid values fail with a message naming the variable. Secrets come only from the
environment. The database password is redacted from the configuration's `toString`.

### Database and migrations

**The runtime owns migration orchestration. Each participant owns its own migrations.** A
runtime version and the database shape it requires are one compatibility unit.

| | Runtime migrations | Application migrations |
|---|---|---|
| Owner | commerce-runtime | the concrete application |
| Objects | the `commerce` schema and everything in it | the application's own schemas and objects |
| Discovered at | `classpath:db/commerce`, inside the runtime jar, internally | `ApplicationContributions.migrationLocations` |
| Schema history | `commerce.flyway_schema_history` | `public.flyway_schema_history` |
| Runs | first | only after the runtime migrations succeeded |

- **Two independent streams.** Each has its own history and version space: the
  application's `V1` coexists with the runtime's `V1`, and an application never numbers
  its migrations after the runtime's. Upgrading `commerce-runtime` is enough to pick up its
  new migrations. The application never lists them, and a contributed location that
  overlaps `db/commerce` (such as `classpath:db`) is rejected.
- **Ownership.** Sharing one database is not shared ownership. Runtime migrations change
  only runtime-owned objects and never touch application tables, indexes, constraints,
  sequences, or views. Application migrations may *reference* runtime-owned objects (for
  example a foreign key to the key of a `commerce` table) but never alter them. This is an
  architectural contract enforced by review, not by inspecting SQL.
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
      MigrationLifecycle(dataSource, application.migrationLocations).migrate()
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
  unqualified names into the commerce schema. Application migrations run with `public` as
  their default schema.

The runtime migration stream starts with `V1__commerce_baseline.sql` and adds the
offerings tables in `V2__offerings_snapshots.sql`. The latter creates
`commerce.offerings_snapshots`, `commerce.offering_categories`, and `commerce.offerings`.
`V3__principal_sessions.sql` creates `commerce.principal_sessions` for
[sessions](#sessions-and-authorization): the session ID, the principal as a
runtime-controlled kind (`USER` or `SERVICE`) plus its UUID, the unique SHA-256 token
digest (never the token), and the creation, expiry, and revocation times, with indexes for
revoking a principal's sessions and for finding expired ones. Development databases that
applied an unreleased draft of `V3` (with lowercase principal kinds) fail Flyway validation
on its changed checksum and must be recreated; repairing the history alone would leave
the draft's constraint in place. These are runtime-owned
published structures; later changes follow the compatibility contract above.

> **Pre-release reset.** 0.0.4 created `commerce.customers` and 0.0.5 dropped it again.
> Before any real consumer existed, those two migrations were collapsed into the `V1`
> baseline. Databases migrated by 0.0.4 or 0.0.5 fail validation against this release and
> must be recreated. This was a one-time exception to the immutable-history rule.

### Offerings snapshots

`CommerceRuntimeContext.offeringsSnapshotRepository` exposes the append-only
`OfferingsSnapshotRepository`. Each method takes the caller's `Transaction` first:

```kotlin
context.transactor.inTransaction { transaction ->
    context.offeringsSnapshotRepository.insert(transaction, snapshot)
    // Application-owned writes may use transaction.handle here too.
}

val latest = context.transactor.inTransaction { transaction ->
    context.offeringsSnapshotRepository.retrieveLatestVersion(transaction, catalogId)
}
```

`retrieveVersion(transaction, reference)` retrieves an exact revision or returns null;
`retrieveLatestVersion` returns the highest revision for one catalog or null. `insert`
never updates existing rows. The `(catalog_id, revision)` primary key rejects duplicate
revisions, and a self-reference requires a successor's immediate predecessor to exist.
Categories and offerings use explicit positions, so round trips preserve snapshot order.
Price forms have stable `FIXED`, `PER_QUANTITY`, and `PER_DURATION` discriminators;
amounts use exact PostgreSQL `numeric`, and durations store seconds plus nanoseconds.
The repository maps rows to domain values explicitly through `OfferingsSnapshot.restore`.
It does not open a connection or transaction. A duplicate revision is reported as
`CommerceFailure.Conflict`.

### Offerings catalog operations and HTTP

`io.github.castab.commerce.runtime.offering` provides `CreateOfferingsCatalog`,
`AddOfferingCategory`, `AddOffering`, `GetOfferingsCatalog`,
`GetOfferingsCatalogRevision`, `ListOfferingCategories`, `GetOfferingCategory`,
`ListCategoryOfferings`, `ListOfferings`, and `GetOffering`. Every operation accepts an
explicit `OfferingsCatalogId` (the revision query accepts a reference containing it).
Commands each open one transaction through `Transactor`, read the latest catalog, derive
an immutable immediate successor, and append it through `OfferingsSnapshotRepository`.
Initialization creates an empty revision 1. A missing catalog or item is `NotFound`;
duplicate keys and duplicate initialization are `Conflict`. Concurrent writers that
derive the same successor revision receive `Conflict` on the losing insert. The runtime
does not retry or merge it; the caller may reload and decide what to do.

The HTTP capability is opt-in. A concrete application can bind a catalog and compose its
original http4k contract routes into its own contract. A write-capable binding carries the
application's `AccessControl` (its authentication filter and `PermissionResolver`):

```kotlin
val access = AccessControl(sessionAuthentication(context.sessions, sessionCookie), permissionResolver)
val catalog =
    offeringsHttpCapability(
        context,
        OfferingsHttpBinding(
            catalogId = myCatalogId,
            basePath = "/offering-catalog",
            operationIdPrefix = "primaryOfferings",
            access = OfferingsHttpAccess.ReadWrite(access), // or OfferingsHttpAccess.ReadOnly
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

At the chosen base path, the capability offers:

| Method | Relative path | Purpose |
|---|---|---|
| GET, POST | `/` | Latest catalog; initialize empty catalog |
| GET | `/revisions/{revision}` | Exact historical catalog |
| GET, POST | `/categories` | List; append category |
| GET | `/categories/{categoryKey}` | Category |
| GET | `/categories/{categoryKey}/offerings` | Ordered category offerings |
| GET, POST | `/offerings` | List; append offering |
| GET | `/offerings/{offeringKey}` | Offering |

`OfferingsHttpAccess.ReadOnly` omits the three POST routes entirely and needs no
authorization dependency. `OfferingsHttpAccess.ReadWrite(accessControl)` exposes them, and
every write requires an authenticated principal that currently holds
`CommercePermissions.OfferingsManage` (`commerce.offerings.manage`): `401 unauthenticated`
without a principal and `403 forbidden` without the permission, before the request body is
read. The runtime declares that requirement; the application's `AccessControl` supplies the
authentication and the `PermissionResolver` that evaluates it, so which roles grant the
permission is the application's decision. A write-capable binding cannot be built without
one. Reads carry no permission requirement: they are as public as the place the host mounts
them, and the host may still wrap them in its own filters. The write routes document `401`
and `403` in OpenAPI. The binding's catalog ID supplies all write targets; request DTOs
have no catalog ID or revision fields. The operation ID prefix prevents collisions when
two catalogs are mounted in one host contract.

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

Path parameters fail the same way as bodies: a non-integer revision is
`malformed_request` (400); a revision below 1, or a category or offering key that is
blank or contains whitespace, is `validation_failed` (422); a well-formed but absent
revision, category, or offering is `not_found` (404). Each route's OpenAPI metadata lists
the error statuses among these that the route can actually return. It does not evaluate
an `OfferingsEngine`, own any application catalog contents, or implement update/delete
commands. Released `V2__offerings_snapshots.sql` remains unchanged.

### Transactions

`Transactor.inTransaction { transaction -> ... }` commits when the block returns and rolls
back when it throws, rethrowing the original exception. Repository methods take the
`Transaction` as their first parameter. An operation that coordinates several persistent
concepts does everything inside one `inTransaction` call. For example: payment recorded,
allocation recorded, reconciliation derived, booking policy evaluated, booking and
document transitioned. A nested `inTransaction` call opens a separate transaction, so pass
the existing `Transaction` down instead.

### HTTP and errors

Bodies are JSON (`CommerceJson`): unknown request fields are ignored, defaults are
written, and `null` optionals are omitted. Transport DTOs are `@Serializable` classes in
the runtime. Routes translate between DTOs and domain values explicitly.

Every error has one shape:

```json
{"code": "validation_failed", "message": "Financial document 5f0c6a7e-... must contain at least one line item"}
```

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

The runtime manages what happens **after** an application has proven who a caller is. It
never sees credentials.

```text
commerce-domain        Principal, UserId, ServiceId, roles, permissions,
                       PermissionResolver, PrincipalId.can

commerce-runtime       authenticated-session lifecycle (SessionManager)
                       session persistence (commerce.principal_sessions)
                       token issuance and resolution
                       HTTP principal context (authenticatedPrincipal)
                       permission enforcement (requirePermission, AccessControl)

concrete application   credential storage and verification
                       login and logout endpoints
                       OAuth, passkeys, or any other identity proof
                       its PermissionResolver (principals, role assignments, role definitions)
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
    → SessionManager.resolve(token)  (digest lookup; unknown, expired, or revoked → 401)
    → authenticatedPrincipal         (http4k request context)
    → requirePermission(permission)  (PrincipalId.can with the application's PermissionResolver → 403)
    → business handler
```

```kotlin
val access = AccessControl(sessionAuthentication(context.sessions, sessionCookie), permissionResolver)

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
| `runtime.http`: `requirePermission`, `requireAuthenticatedPrincipal`, `AccessControl` | Authorization filters and the declarative `public()` / `authenticated()` / `requirePermission(...)` route protection. |

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

**Runtime capabilities declare permissions; applications supply the resolver.** A
runtime-provided HTTP capability that exposes protected operations declares the commerce
permission each one requires and receives the application's `AccessControl` (or, where it
needs no authentication of its own, its `PermissionResolver`) explicitly when the
application composes it, as the Offerings write binding does. `commerceRuntime(...)` takes
no global resolver and `CommerceRuntimeContext` does not carry one: public capabilities need
no resolver, and a protected capability cannot be composed without one. Evaluation always
goes through `PrincipalId.can`, and nothing about missing authorization configuration ever
means "allow".

**Principal persistence contract.** commerce-runtime persists an explicit set of supported
`PrincipalId` representations: today `UserId` as `USER` and `ServiceId` as `SERVICE`, each
with its UUID. Adding a `PrincipalId` subtype to commerce-domain does not make it
session-persistable. Runtime support must deliberately add the encoding and decoding
mapping (`PrincipalIdColumns`, whose exhaustive `when` over the sealed `PrincipalId` stops
compiling until it does) and a runtime migration that widens the `principal_kind` check
constraint. This is intentional, so authentication identities fail closed rather than being
serialized implicitly: there is no class-name, `toString`, or generic serialization, and no
registry of principal kinds.

**Session cleanup.** Expired and revoked rows stay in `commerce.principal_sessions`; they
authenticate nothing. Purging old inactive rows would be a runtime capability (an operation
over the `expires_at` index); when and how often to run it would be application or
deployment policy. The runtime provides neither yet, and it will not schedule background
jobs itself.

Sessions may belong to any `PrincipalId`, human or service, and authorization treats them
alike. That generality is deliberate, but this is not service authentication: service
credentials (API keys and the like) are a separate, future capability that would meet
sessions only at `PrincipalId`.

`/health` and `/ready` stay explicitly public and never authenticate. Application routes are
protected only where the application applies these filters: the runtime does not
authenticate globally.

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
   `ApplicationContributions.migrationLocations`, keyed by the application's own booking
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

- **Sessions only, and no credentials.** The runtime issues, resolves, and revokes sessions
  and enforces permissions on the routes an application protects. It verifies no
  credentials, and has no login endpoints, service credentials (API keys), refresh tokens,
  JWTs, sliding expiry, or CSRF protection. Expired and revoked session rows are kept;
  there is no purge operation yet (see [Session cleanup](#sessions-and-authorization)).
- No booking, financial document, payment, refund, or reconciliation orchestration or
  persistence yet. In particular, a JDBI implementation of the domain's
  `FinancialDocumentHistory` SPI is the natural next persistence step.
- No idempotency keys, outbox or events, Server-Sent Events, or scheduled jobs yet.
- No payment provider integration. Providers (e.g. a future `stripe-adapter`) sit
  behind the provider-neutral contract in `commerce-domain`. This module will never
  depend on a provider SDK.
- No executable or deployable packaging, by design. Concrete applications own `main()`,
  build their own runnable jar (for example with the Shadow plugin) and image. This
  library publishes a plain jar.
- `ApplicationContributions` covers routes and migrations only, and together with
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
validation, and startup gating on failures), and commit and rollback of several
application-owned writes sharing one `Transaction`. `CommerceRuntimeSpec` composes the
runtime the way a concrete application does: it supplies explicit
`ApplicationContributions` (an application migration and routes that receive
`CommerceRuntimeContext` and persist an application-owned table through
`context.transactor`), starts Jetty, exercises it over real HTTP and PostgreSQL, including
error handling and rollback, and closes it.

`OfferingsSnapshotRepositorySpec` proves append-only round trips, revision constraints,
price subtype reconstruction, ordering, and atomic commit and rollback of an offering
snapshot with an application-owned row.

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

Database specs run against a real PostgreSQL 18 that the build starts through the Docker CLI.
See [Building and testing](../README.md#building-and-testing).
