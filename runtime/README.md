# commerce-runtime

```text
io.github.castab:commerce-runtime:<version>
```

The opinionated, reusable runtime of the [`commerce`](../README.md) project. Concrete
commerce applications are assembled from it. It turns the vocabulary and invariants of
[`commerce-domain`](../domain/README.md) into working infrastructure: application
operations, explicit transactions, PostgreSQL persistence, HTTP conventions on http4k and
Jetty, one error contract, health checks, configuration, and an explicit composition root.

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

> **Status: foundation.** This first iteration establishes the architecture and the
> infrastructure, and shows the full request path with one capability (customers). It
> does not yet orchestrate bookings, financial documents, or payments. See
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
            routes = { context -> listOf(myApplicationRoutes(context.transactor, context.customers)) },
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
and its own routes. The routes are built
from the shared `CommerceRuntimeContext` (the configuration, the `Transactor`, and the
commerce repositories), so an application's writes and commerce writes can share one
transaction. The runtime owns the error handling around every route.

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
- The runtime (or the concrete application) owns relationships that only coordinate
  independently meaningful domain concepts, for example which financial documents belong
  to a booking. The domain stays free of them:

  > A relationship belongs in `commerce-domain` when one domain concept cannot meaningfully
  > express its semantics or invariants without the other concept. Relationships that
  > coordinate otherwise independently meaningful concepts belong to the consuming
  > application/runtime layer.

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
| `runtime` | `commerceRuntime(...)`, `CommerceRuntime`, `ApplicationContributions`, and `CommerceRuntimeContext`. |
| `runtime.config` | `CommerceRuntimeConfiguration`: HOCON loading, environment overrides, and validation. |
| `runtime.persistence` | `createDataSource` (HikariCP), `MigrationLifecycle` (the migration phase), `Transactor` and `Transaction`, and PostgreSQL error helpers. |
| `runtime.operation` | Support for operations (use cases such as `CreateCustomer`): `CommerceFailure`, the expected failures of operations, and `validating`. |
| `runtime.http` | `CommerceJson`, `jsonBody`, the error contract and `CommerceErrorHandling` filter, and health routes. |
| `runtime.customer` | The representative capability: `CustomerRepository`, `CreateCustomer` / `GetCustomer`, and the customer routes and DTOs. |

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
  example a foreign key to `commerce.customers (id)`) but never alter them. This is an
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

Current runtime-owned tables: `commerce.customers` (id, name, email). Its `id` primary key
is part of the published database contract.

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
{"code": "validation_failed", "message": "Customer name must not be blank"}
```

| Category | Status | `code` | Raised by |
|---|---|---|---|
| Malformed request | 400 | `malformed_request` | unreadable JSON, missing fields, unparsable path values (http4k `LensFailure`) |
| Validation failure | 422 | `validation_failed` | `CommerceFailure.ValidationFailed`, typically a domain `require` inside `validating { }` |
| Not found | 404 | `not_found` | `CommerceFailure.NotFound`, or no matching route |
| Conflict | 409 | `conflict` | `CommerceFailure.Conflict`, e.g. a unique-key violation |
| Illegal transition | 409 | `illegal_transition` | `CommerceFailure.IllegalTransition` |
| Invariant violation | 422 | `invariant_violated` | `CommerceFailure.InvariantViolated` |
| Internal failure | 500 | `internal_failure` | anything else; logged, never described |

Messages come only from `CommerceFailure`, whose messages are written for callers. SQL
text, stack traces, and exception details of unexpected failures never reach a response.

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

These routes are served by every application built on the runtime. There is currently no
way to disable them; see [Current limitations](#current-limitations).

| Endpoint | Behavior |
|---|---|
| `POST /customers` `{"name", "email"}` | Creates a customer. `201` with the customer and `Location`. Invalid values are `422`. |
| `GET /customers/{customerId}` | `200` with the customer, or `404`. |
| `GET /health`, `GET /ready` | See [Health](#health). |

Customers are the representative capability because every known consumer, with or
without bookings, has them. They exercise every layer: route, DTO, domain translation,
operation, transaction, repository, migration, and error mapping.

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
  about customers, financial documents, payments, or their HTTP and persistence requires a
  booking, and a point-of-sale application contributes no booking functionality at all.
- A booking type parameter, if one is introduced, stays confined to booking APIs. It must
  not spread into customer, financial, or payment APIs.

Responsibilities this foundation has identified for the future extension:

1. **Detail schema.** Its own tables, contributed through the existing
   `ApplicationContributions.migrationLocations`, keyed by the domain's `Booking.Id`.
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

The booking-to-financial-document association is not an extension concern. The runtime
will own it as an application-level relationship.

## Current limitations

- **No authentication or authorization over HTTP.** The staff principals, roles, and
  permissions exist in `commerce-domain`. Wiring them into requests is a separate
  iteration. Operations are plain classes with explicit inputs, so a principal can be
  added to their commands and checked with `PrincipalId.can` without restructuring. Until
  then, deploy applications built on the runtime only behind a trusted boundary.
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
- **Capability selection is intentionally deferred.** The commerce routes (currently the
  customer endpoints) are always enabled, and `/health` and `/ready` are runtime
  infrastructure. Applications cannot yet choose which commerce capabilities they serve.
  That will be designed only after concrete consumers show which compositions they need.
  There are no capability flags or capability framework until then.

## Tests

```bash
./gradlew :runtime:test
```

The tests are this repository's only executable consumer of the runtime. The specs cover
configuration loading and validation, the error contract, health and readiness, DTO
serialization, the migration contract (internal runtime discovery, runtime-before-
application ordering proven by a real dependency, independent version spaces, idempotent
and concurrent migration, validation, and startup gating on failures), transaction
commit and rollback across commerce and application tables, and the customer repository.
`CommerceRuntimeSpec` composes the runtime the way a concrete application does: it
supplies explicit `ApplicationContributions` (an application migration and a route that
shares a transaction with a commerce repository), starts Jetty, exercises it over real
HTTP and PostgreSQL, and closes it. Database specs run against a real PostgreSQL 18 that
the build starts through the Docker CLI. See
[Building and testing](../README.md#building-and-testing).
