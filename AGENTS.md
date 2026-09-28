# AGENTS.md

The architectural contract for contributors and coding agents working in this repository.
Read this before changing anything under `domain/src/main` or `runtime/src/main`, and
before adding a dependency, a module, or a lifecycle concept.

[`README.md`](README.md) introduces the project, [`domain/README.md`](domain/README.md)
and [`runtime/README.md`](runtime/README.md) introduce each artifact to adopters. This file
explains the rules and why seemingly reasonable changes can be architecturally wrong.

# Project architecture

`commerce` is a Gradle multi-project build with exactly two modules:

| Gradle project | Published artifact | Role |
|---|---|---|
| `:domain` | `io.github.castab:commerce-domain` | Pure commerce vocabulary and invariants. Depends on `kotlin-stdlib` only. |
| `:runtime` | `io.github.castab:commerce-runtime` | Opinionated, reusable runtime from which concrete commerce applications are assembled: http4k on Jetty, PostgreSQL via HikariCP, JDBI, and Flyway, kotlinx.serialization, Hoplite configuration, transactions, HTTP conventions, principal sessions and permission enforcement, explicit composition. A library, not an application. |

The root project coordinates shared build configuration and contains no sources. There
is no executable module in this repository.

```text
commerce-domain                  vocabulary, facts, invariants, protocols
      │
      ▼
commerce-runtime                 reusable, opinionated runtime machinery (a library)
      │
      ▼
concrete commerce application    the consuming project: owns main() and the process
```

These rules are non-negotiable without an explicit decision from the maintainer.

## Runtime identity

`commerce-runtime` is a reusable library/runtime, not a concrete application. It must not
define a default business application or own a production `main()` entry point. Concrete
applications depend on the runtime, explicitly provide application contributions and
extensions, and own their executable and process lifecycle.

- `:runtime` applies `java-library`, never Gradle's `application` plugin. There is no
  `./gradlew :runtime:run`.
- Do not introduce generic development executables into `commerce-runtime` (a
  `DevelopmentServer`, `RuntimeMain`, `CommerceMain`, `DefaultCommerceApplication`,
  `DemoApplication`, or any other `main()`) merely to make the runtime directly runnable.
  Verify runtime composition through tests or concrete consuming applications. Do not add
  an `app/`, `server/`, `runner/`, or example-application module to this repository
  without an explicit decision.
- `commerceRuntime(configuration, application)` has no default for `application`. Never
  add one: the runtime plus configuration alone is not an application, and every consumer
  must state its contributions, even when that is `ApplicationContributions()`.
- The runtime may start and stop the resources it creates (Jetty, the connection pool) when
  a concrete application invokes it. It does not own the process: no shutdown hooks, no
  blocking of the calling thread, no command-line handling.
- Application-specific concepts, such as catering, detailing, repair, grooming, or
  point-of-sale semantics, belong to concrete consuming applications, not
  `commerce-runtime`.
- **Configuration ownership.** `commerce-runtime` defines the configuration it requires
  (`CommerceRuntimeConfiguration`: model, defaults, validation) and may provide loading
  machinery (`CommerceRuntimeConfiguration.load()`). The concrete application supplies
  the deployment configuration: its `application.conf` and environment. Never ship an
  `application.conf` (or any other deployment configuration) in the runtime's
  `src/main/resources`.
- **Logging ownership.** `commerce-runtime` owns its logging calls and the facade it
  compiles against (Kotlin Logging on the SLF4J API). The concrete application owns the
  SLF4J provider (Logback or any other implementation) and the production logging
  configuration. Never add an SLF4J provider (`logback-classic`, `slf4j-simple`,
  `log4j-slf4j2-impl`, ...) to the runtime's `api`, `implementation`, or `runtimeOnly`
  dependencies. The runtime's tests may choose Logback, but only as `testRuntimeOnly`.
  Never ship a `logback.xml`, `logback-test.xml`, or other logging configuration in the
  runtime's `src/main/resources`. Do not remove or weaken the runtime's logging calls to
  compensate; this rule is about who owns the implementation, not whether the runtime
  logs.
- The runtime's `src/main/resources` holds only runtime-owned artifacts: its Flyway
  migrations in `db/commerce/`. Configuration and logging resources that tests need live
  in `runtime/src/test/resources` and are never published.

## Dependency direction

`:runtime` may depend on `:domain`. `:domain` must never depend on `:runtime`, directly or
transitively. Within the build the dependency is `api(project(":domain"))`. It is never
resolved from GitHub Packages.

## Domain purity

Do not introduce HTTP, persistence, serialization, configuration, logging, framework, or
deployment concerns into `:domain`. A consumer such as a payment adapter must be able to
depend on `commerce-domain` without acquiring http4k, Jetty, JDBI, HikariCP, PostgreSQL,
Flyway, Hoplite, `commerce-runtime`, or any other runtime infrastructure. `:domain:check` enforces this
through `verifyRuntimeDependencies`. Do not annotate or alter domain types to make HTTP
serialization or persistence convenient; translate explicitly in `:runtime`.

## Semantic preservation

Before changing an existing domain model, explain what the affected type means before the
change and what it means afterward. Do not mechanically move fields or relationships
between domain models merely to satisfy an implementation request. Treat existing type
distinctions as intentional unless evidence shows otherwise. If a proposed change alters
the semantic meaning of a type, call that out explicitly during planning. Restructuring
work (moving files, changing builds) must not redesign domain semantics.

## Application relationships

A relationship belongs in `commerce-domain` when one domain concept cannot meaningfully
express its semantics or invariants without the other concept. Relationships that
coordinate otherwise independently meaningful concepts belong to the consuming
application layer. Do not add relationships to `commerce-domain` merely because
applications commonly coordinate those concepts. Booking and financial documents, for
example, stay independent in the domain; the concrete application establishes and
persists their association, using the runtime's shared transaction.

The governing principle:

> The domain owns independent commerce facts and their invariants. The application owns
> business entities and the relationships between those facts.

## Application-owned entities and relationships

`commerce-domain` does not define a generic customer entity or customer identifier.
Customer profiles, customer IDs, booking records, booking contacts, service or event
locations, inquiries, and business-specific booking data belong to concrete consuming
applications. Do not reintroduce them, and do not introduce stand-ins under other names
(`Party`, `AccountHolder`, `CommerceCustomer`, `Subject`, `Owner`, `Actor`, `Client`,
`Consumer`, ...) unless a domain operation genuinely needs the concept for one of its own
invariants.

The domain must not introduce generic fields merely to attach those concepts to commerce
facts. That includes untyped escape hatches: no `metadata: Map<String, String>`,
`details: JsonObject`, `context: Any`, or similar on financial documents, lifecycle types,
runtime persistence, or any other generic model. Business-specific data stays strongly
typed in the concrete application.

```text
                    CONCRETE APPLICATION

Customer ───────────────┐
Inquiry ────────────────┼──── application-owned relationships
Booking ────────────────┤
                         │
                         ▼
               independent commerce facts
                         │
       ┌─────────────────┼─────────────────┐
       ▼                 ▼                 ▼
FinancialDocument   BookingLifecycle    Payments/etc.
```

The diagram is conceptual, not a required persistence design.

## Independent financial documents

`FinancialDocument` is independent of customers, bookings, inquiries, and
application-specific ownership. Applications persist relationships to financial
documents externally, typically keyed by the document's `id` or `reference`. Do not add
generic ownership, application-relationship reference, recipient, or metadata fields
(`customerId`, `ownerId`, `subjectId`, `partyId`, `customerReference`,
`externalReference`, `contextId`, `relationshipId`, `metadata`, ...) to
`FinancialDocument` to recreate application relationships indirectly. This prohibition
concerns references that attach application entities to a document, not the document's
own snapshot references (`FinancialDocumentReference`, `reference`,
`previousReference`), which are legitimate financial-document concepts and stay as they
are. Issuance-time recipient information (`BillTo`, `InvoiceRecipient`, ...) is a
separate, undecided design question; see [Open questions](#open-questions).

## Booking boundary

`io.github.castab.commerce.booking.lifecycle` defines reusable booking lifecycle topology
only. Concrete booking models belong to applications and implement lifecycle phase
interfaces directly. Do not add generic booking record models (`Booking`, a booking ID,
contacts, locations, addresses) without a new explicit architectural decision.
`BookingLifecycle.Active.InitialRequest` is a generic pre-quote phase, not an inquiry and
not a financial estimate; relating an application's inquiry, its lifecycle phase, and any
estimate it issues is application policy.

## Runtime boundary

`commerce-runtime` provides reusable infrastructure and orchestration for commerce-domain
concepts. It does not provide generic customer persistence, customer CRUD, customer HTTP
endpoints, or a generic application data model. `commerce-runtime` owns the shared
transaction abstraction; application repositories may use the same `Transactor` and
`Transaction`. Relationships between application entities and commerce-domain facts are
application-owned.

The runtime's first commerce-owned repository persists immutable offerings snapshots in
the `commerce` schema. It uses the caller's `Transaction`; integration tests prove that
offering snapshots and application-owned rows commit or roll back together. The runtime
migration stream has the empty `V1__commerce_baseline.sql`, the offerings `V2` migration,
the principal sessions `V3` migration, and the authorization directory `V4` migration. Do not create placeholder commerce tables or fake
repositories.

## Runtime opinionation

It is acceptable, and intended, for `commerce-runtime` to establish conventions around
http4k, Jetty, PostgreSQL, JDBI, Flyway, kotlinx.serialization, Hoplite, transactions, and
HTTP behavior. Do not weaken useful abstractions to support hypothetical alternative
frameworks. The runtime rules are:

- Routes translate only: request DTO → domain values → one operation → response DTO. No
  SQL and no orchestration in routes.
- Operations (use cases such as issuing an invoice or recording a payment) orchestrate and own the transaction
  boundary through `Transactor`. Repositories take the caller's `Transaction`. Operation
  support (`CommerceFailure`, `validating`) lives in `io.github.castab.commerce.runtime.operation`.
  In this repository, "application" means the concrete consuming application; do not use
  it to name runtime packages or runtime concepts.
- Expected failures are `CommerceFailure` subclasses, whose messages are written for
  callers. `CommerceErrorHandling` maps every failure to the documented
  `{"code", "message"}` contract. SQL, stack traces, and implementation detail never reach
  a response.
- Transport DTOs are `@Serializable` classes in `:runtime`, serialized through
  `CommerceJson`.
- Configuration is HOCON through Hoplite plus explicit, documented environment overrides.
  The application supplies the file; the runtime ships none. Never commit secrets.
- Composition is explicit in `commerceRuntime(...)`. No DI framework, no annotation
  scanning. It composes the commerce capabilities with the caller's explicit
  `ApplicationContributions`.

## Provisional application-extension seam

`ApplicationContributions` (Flyway locations, routes, and permission definitions) and `CommerceRuntimeContext` (the
configuration, `Transactor`, offerings snapshot repository, `SessionManager`, and authorization directory handed to
contributed routes) are
the **provisional** application-extension seam. They let a concrete application run on
the shared runtime today, and they are expected to change once the booking extension and
the other capabilities are designed from real consumer requirements. Whether the seam
becomes a `CommerceApplication`, a `CommerceExtension`, or a `BookingExtension<B>` is
undecided. `ApplicationContributions` must stay broader than booking: a point-of-sale
application contributes no booking functionality. Treat them accordingly:

- Do not treat either type as the settled extension contract, and do not build the future
  booking extension by piling fields or callbacks onto them.
- Add a contribution point or a `CommerceRuntimeContext` member only when a concrete consumer
  needs it. Say in the change which consumer, and why the existing seam is insufficient.
- Prefer exposing operations and repositories that stay stable over exposing
  more infrastructure.

**Infrastructure types in the public API.** The runtime's public API currently exposes
JDBI and HikariCP types: `Transaction.handle` (`org.jdbi.v3.core.Handle`), so that
application repositories can join a commerce transaction, and `createDataSource`
(`com.zaxxer.hikari.HikariDataSource`). That is why `jdbi3-core` and `HikariCP` are `api`
dependencies. This exposure is **intentional but revisitable**. It is a deliberate
consequence of the opinionated PostgreSQL/JDBI stack, not a precedent for leaking more
infrastructure.

- Do not casually expand it. Do not add public members that expose `Jdbi`, `Handle`,
  `HikariDataSource`, Flyway, Jetty, or Hoplite types, and do not promote an
  `implementation` dependency to `api`, without stating why in the change and updating
  this section and `runtime/README.md`.
- Keep new infrastructure types internal or private by default.
- A future iteration may narrow this surface, for example by wrapping the handle in a
  commerce-owned repository-facing type. Do not make changes that would make such a
  narrowing harder without a reason.

## Known-use-case generalization

Generalize from the known consumers: catering, mobile detailing, computer repair,
pet/service appointments, and point of sale. Code belongs in generic `commerce-runtime`
only if it makes sense for more than one of them. Issuing invoices, recording, allocating,
and refunding payments, associating financial documents with bookings, lifecycle
transitions, HTTP error representation, and PostgreSQL transactions qualify. Guest counts,
catering packages, vehicle make or model, paint correction, pet breed, device serial
numbers, and diagnostic notes do not. Do not build abstractions solely for hypothetical
consumers.

## Booking extensibility

Do not introduce concrete application-specific booking types (`CateringBooking`,
`DetailingBooking`, `RepairBooking`, `GroomingBooking`, `PetSalonBooking`, ...) into either
generic module. Application-specific booking details must be strongly typed and
compile-time known to the concrete application. Never model them as opaque JSON
(`type: String, details: JsonObject`). The extension seam is an open design question (see
[Open questions](#open-questions)); do not invent a large `BookingExtension<B>` API
incidentally. If a booking type parameter is ever introduced, keep it inside booking APIs.
It must not spread into financial or payment APIs.

## Booking optionality

Do not make booking mandatory for financial or payment functionality. Booking is one
commerce capability, not the root of commerce. A point-of-sale application uses
invoices, payments, allocations, refunds, and reconciliation without a booking. Every runtime operation, table, and endpoint outside booking must work with no
booking at all.

## Provider neutrality

Do not leak Stripe or another payment provider into generic commerce models or runtime
APIs: no `StripePayment`, `StripeRefund`, `PaymentIntent`, `Charge`, or provider SDK
dependency in either module. Providers sit behind the provider-neutral contract in
`io.github.castab.commerce.payment.adapter`. A provider adapter depends on
`commerce-domain` only.

## Authorization in the runtime

The staff principals, roles, and permissions remain domain concepts in `:domain`. Do not
remove or redesign them, and do not add sessions, tokens, cookies, credentials, or any
other authentication concept to `:domain`. Keep operations explicit about their inputs so
a principal can be added without restructuring.

Sessions are runtime infrastructure in `io.github.castab.commerce.runtime.session`. The
concrete application proves identity (it owns credentials, their verification, and login
endpoints) and hands the resulting `PrincipalId` to `context.sessions.create(...)`; the
runtime owns everything after that. `CommerceRuntimeContext.sessions` was added for the
first consuming application (`fionas-commerce`), which must issue sessions after verifying
its own credentials.

`CommerceRuntimeContext.authorization` is the reusable principal and RBAC directory
needed by `fionas-commerce` and other staff-based consumers. Its PostgreSQL repositories
remain internal. The runtime persists human users, service identities, statuses, role
definitions, permission mappings, and assignments; it provides live resolver ports and
transaction-aware administration. Credentials, login and bootstrap policy, and vertical
staff profiles remain application-owned. `ApplicationContributions.permissionDefinitions`
adds code-backed permission metadata; runtime composition rejects duplicate keys, and
role mutations reject unknown keys. `PrincipalRead` and `PrincipalManage` cover both principal
kinds; `RoleRead`, `RoleManage`, and `RoleAssign` are distinct. Disabling either kind
revokes all its sessions in the same transaction. An assigned role cannot be deleted.
No conventional role, including `Administrator`, has implicit grants.

Sessions may be created only for known, ACTIVE runtime principals. Resolution also checks
current principal existence and status; an otherwise valid token for a missing or
disabled principal does not authenticate. Sessions and the directory share internal
principal persistence to avoid a construction cycle. After both migration streams and
before HTTP composition, every stored role-permission grant must exist in the current
`PermissionCatalog`; unknown keys fail startup with their names. Live resolution checks
again and fails closed if a grant changes after startup. An application removing one of
its software-defined permissions must remove the stale grants in its application
migration before startup validation. This is permitted data maintenance in the runtime
table, not ownership of its DDL or schema. `RoleAssign` has no grant ceiling or role
hierarchy in this version.

- **A session establishes identity, not authority.** Never store permissions, roles, or
  principal status in a session. Authorization always asks the current
  `PermissionResolver` through `PrincipalId.can`, on every request.
- **Tokens are secrets.** A `SessionToken` comes from `SecureRandom` (32 bytes, base64url)
  and is unrelated to the `SessionId` and the principal. Only its SHA-256 digest is
  stored; never persist or log a raw token, or put one in an exception message or
  `toString`. Session tokens are not passwords: do not use bcrypt, Argon2, or PBKDF2 for
  them. The digest type and the repository stay internal so that no public API accepts
  stored token material.
- **Expiry is fixed** (`sessions.lifetimeMinutes`) and judged by the runtime's clock,
  never the database clock. Do not add sliding expiry, refresh tokens, or JWTs
  incidentally.
- **Principal persistence is an explicit, fail-closed contract.** commerce-runtime persists
  an explicit set of supported `PrincipalId` representations (`USER` and `SERVICE`, each
  with its UUID). Adding a `PrincipalId` subtype to commerce-domain does not make it
  session-persistable: runtime support must deliberately add the encoding and decoding in
  `PrincipalIdColumns` (whose exhaustive `when` stops compiling until it does) and a new
  runtime migration widening the `principal_kind` check constraint. Never replace this
  with class names, `toString`, generic serialization, or a registry, and do not change
  the domain to accommodate hypothetical non-UUID principals.
- **Runtime capabilities declare permissions; applications supply `AccessControl`.** A
  runtime HTTP capability that exposes protected operations declares the commerce
  permission each requires and receives the application's `AccessControl` explicitly at
  composition, as `OfferingsHttpAccess.ReadWrite` does for
  `CommercePermissions.OfferingsManage`. The application can use
  `context.authorization.permissionResolver`. Never add a global resolver to
  `commerceRuntime(...)`, never let missing authorization mean
  "allow", and keep public capabilities free of an irrelevant resolver.
- **HTTP.** `SessionTokenExtractor` (transport) is separate from `SessionManager`
  (resolution). `BearerSessionToken` and `SessionCookie` are adapters; cookies are not the
  session model. `sessionAuthentication` sets the `authenticatedPrincipal` request-context
  lens, which only runtime authentication filters may write. Authentication precedence
  follows composition order: once a runtime authentication filter establishes the
  principal, nested `sessionAuthentication` filters and `AccessControl` instances reuse it
  (an `AccessControl`'s own authentication filter is only a fallback) and never replace it
  with another transport's credentials; permissions are still evaluated per request. Do
  not add logic that reconciles multiple credential sources, and do not use filter order
  to require a particular authentication mechanism: transport is not authorization. `requirePermission` answers
  `401` without a principal and `403` without the permission; `AccessControl` declares each
  route `public()`, `authenticated()`, or `requirePermission(...)`. Keep all of it fail
  closed, and keep `/health` and `/ready` public.
- **Not service credentials.** A session may belong to any `PrincipalId`, but do not build
  API keys or other service authentication on sessions. That is a separate capability
  that would share only `PrincipalId`.
- Do not add password hashing, login endpoints, OAuth, passkeys, CSRF handling, or
  application-specific cookie names or roles to the runtime.

## Kotlin style

Do not use redundant explicit `public` modifiers anywhere in either module. Write
`data class LineItem(...)`, not `public data class LineItem(...)`.

## Domain module mission

`commerce-domain` (`:domain`) is a reusable library of immutable commerce domain models
and lifecycle APIs, shared by multiple applications. Each domain lives in its own package
beneath `io.github.castab.commerce`:

| Domain | Package | Style |
|---|---|---|
| Booking lifecycle | `io.github.castab.commerce.booking.lifecycle` | A type-level protocol. Adopters' own types implement the phases. The library owns no booking data. |
| Financial documents | `io.github.castab.commerce.financial` | Concrete, library-owned immutable value types (`Estimate`, `Quote`, `Invoice`) whose invariants the library enforces. |
| Offerings | `io.github.castab.commerce.offering` | Immutable catalog snapshots, selection constraints, descriptive price metadata, and an application policy evaluation seam. |
| Payment reconciliation | `io.github.castab.commerce.payment` | Concrete, library-owned immutable records (payments, allocations, allocation reversals, refunds, refund allocations) and reconciliation derived from records the application supplies. |
| Principal authorization | `io.github.castab.commerce.staff` | Human and service identities, distinct UUID-backed principal IDs, extensible roles and permissions, and additive role-based permission resolution. |
| Payment adapter contract | `io.github.castab.commerce.payment.adapter` | Provider-neutral instructions, observations, capabilities, event receipts, and pure decisions at the external-provider boundary. |

The styles are deliberate and not interchangeable. Read the rules for the domain you
are changing: [Booking lifecycle domain](#booking-lifecycle-domain),
[Financial document domain](#financial-document-domain),
[Offerings domain](#offerings-domain),
[Payment reconciliation domain](#payment-reconciliation-domain) (including the adapter
contract), and
[Principal authorization domain](#principal-authorization-domain). The build, dependency,
toolchain, and publication rules apply to the whole repository.

Dependencies between domains are fixed:

```text
booking.lifecycle     independent; imports nothing from the other domains, and nothing imports it
financial             independent; imports nothing from the other domains
offering ────────────→ financial   (Money and LineItem only)
payment ────────────→ financial   (FinancialDocument, FinancialDocumentReference, Money)
payment.adapter ────→ payment, financial   (Money)
staff                 independent of the other domains
```

- The booking lifecycle and the financial documents never import each other. Applications
  compose them (for example, a booking `Quote` phase model that holds a
  `FinancialDocument.Quote`). The library does not.
- The payment domain references financial documents, one way only. `financial` must never
  import `payment`: a document does not own, hold, or know about its settlement. The
  payment domain never imports the booking lifecycle.
- Offerings use `Money` as descriptive price metadata and an application engine produces
  `LineItem`s. Offerings never own or create a `FinancialDocument`.
- No domain package references a customer, booking record, inquiry, contact, or
  location. There is no `customer` package and no generic `booking` record package; only
  `booking.lifecycle` exists.

For the booking lifecycle:

> The library defines what may legally follow a lifecycle phase. The adopting application
> defines whether, when, and how that transition occurs.

If a proposed change describes data, policy, or activity *around* a booking rather than the
booking lifecycle itself, it probably does not belong in the booking lifecycle API.

## Repository layout

| Path | Contents |
|---|---|
| `settings.gradle.kts` | Root project `commerce`; `include("domain")`, `include("runtime")`. |
| `build.gradle.kts` | Shared conventions only: group and version, the Java 25 toolchain, Kotlin JVM target, test setup, ktlint, and common POM and repository metadata. No sources. |
| `gradle.properties`, `gradle/libs.versions.toml` | Build properties and the version catalog for both modules. |
| `domain/build.gradle.kts` | The `commerce-domain` publication and the `verifyRuntimeDependencies` boundary check. |
| `domain/src/main/kotlin/io/github/castab/commerce/booking/lifecycle/BookingLifecycle.kt` | The entire booking lifecycle API. |
| `domain/src/main/kotlin/io/github/castab/commerce/payment/` | The payment reconciliation API: `PaymentMethod.kt`, `ExternalPaymentReference.kt`, `ExternalRefundReference.kt`, `PaymentRecord.kt`, `PaymentAllocation.kt`, `PaymentAllocationReversal.kt`, `RefundRecord.kt`, `RefundAllocation.kt`, `PaymentReconciliation.kt` (payment-level reconciliation and the shared validation helpers), and `FinancialDocumentReconciliation.kt`. |
| `domain/src/main/kotlin/io/github/castab/commerce/payment/adapter/` | The transport-neutral payment adapter contract and pure validation of provider observations. |
| `domain/src/main/kotlin/io/github/castab/commerce/financial/` | The financial document API: `FinancialDocument.kt` (the sealed class, its three stages, and change application), `Version.kt`, `Money.kt`, `LineItem.kt`, `ChangeOrder.kt`, `FinancialDocumentReference.kt`, and `FinancialDocumentHistory.kt` (the history SPI and its lookup extensions). |
| `domain/src/main/kotlin/io/github/castab/commerce/offering/` | The offerings vocabulary, immutable catalog snapshots and revisions, candidate selections, structural validation, and application engine seam. |
| `domain/src/main/kotlin/io/github/castab/commerce/staff/` | Human and service principal identity and authorization: `Principal.kt`, `User.kt`, `ServiceIdentity.kt`, and `Authorization.kt`. |
| `domain/src/test/kotlin/io/github/castab/commerce/booking/lifecycle/BookingLifecycleSpec.kt` | Kotest `FunSpec` for the booking lifecycle contract. |
| `domain/src/test/kotlin/io/github/castab/commerce/booking/lifecycle/fixtures/TestBookingModels.kt` | Test-only "application-owned" booking models. |
| `domain/src/test/kotlin/io/github/castab/commerce/financial/*Spec.kt` | Kotest specs for the financial domain: `FinancialDocumentSpec`, `ChangeOrderSpec`, `FinancialDocumentHistorySpec`, `LineItemSpec`, `MoneySpec`, `VersionSpec`. |
| `domain/src/test/kotlin/io/github/castab/commerce/financial/fixtures/TestFinancialModels.kt` | Test-only money and line item helpers and an in-memory `FinancialDocumentHistory`. |
| `domain/src/test/kotlin/io/github/castab/commerce/offering/OfferingsSpec.kt` | Catalog, selection, and application-engine portability tests. |
| `domain/src/test/kotlin/io/github/castab/commerce/payment/*Spec.kt` | Kotest specs for the payment domain: `PaymentRecordSpec`, `PaymentAllocationSpec`, `PaymentAllocationReversalSpec`, `RefundRecordSpec`, `RefundAllocationSpec`, `PaymentReconciliationSpec`, `FinancialDocumentReconciliationSpec`, and `PaymentDomainSpec` (the end-to-end history and the reflection shape tests). |
| `domain/src/test/kotlin/io/github/castab/commerce/payment/fixtures/TestPaymentModels.kt` | Test-only payment, document, and numeric-comparison helpers. |
| `domain/src/test/kotlin/io/github/castab/commerce/payment/adapter/PaymentAdapterContractSpec.kt` | Kotest coverage for the provider-neutral adapter contract and processing decisions. |
| `domain/src/test/kotlin/io/github/castab/commerce/staff/AuthorizationSpec.kt` | Kotest coverage for staff values, resolver behavior, and fail-closed authorization. |
| `runtime/build.gradle.kts` | The `commerce-runtime` publication (a `java-library`; no `application` plugin), its runtime stack, and the Docker-CLI PostgreSQL build service for tests. |
| `runtime/src/main/kotlin/io/github/castab/commerce/runtime/` | `CommerceRuntime.kt`: the composition root `commerceRuntime(...)`, `CommerceRuntime` (lifecycle of the runtime's resources), `ApplicationContributions`, and `CommerceRuntimeContext`. No `main()`. |
| `runtime/src/main/kotlin/io/github/castab/commerce/runtime/config/` | `CommerceRuntimeConfiguration`: Hoplite/HOCON loading, environment overrides, validation. |
| `runtime/src/main/kotlin/io/github/castab/commerce/runtime/persistence/` | HikariCP data source, `MigrationLifecycle` (the runtime and application Flyway streams), `Transactor`/`Transaction`, the offerings snapshot repository, the internal principal session and authorization repositories and `PrincipalIdColumns`, PostgreSQL error helpers. |
| `runtime/src/main/kotlin/io/github/castab/commerce/runtime/authorization/` | Live authorization directory, permission catalog, administration DTOs and HTTP capability. |
| `runtime/src/main/kotlin/io/github/castab/commerce/runtime/session/` | `PrincipalSession`, `SessionId`, `IssuedSession`, `SessionToken` (and the internal `SessionTokenDigest`), `SessionManager` and its internal PostgreSQL implementation, and `SessionAuthentication.kt` (token extractors, `SessionCookie`, `sessionAuthentication`). |
| `runtime/src/main/kotlin/io/github/castab/commerce/runtime/offering/` | Generic immutable catalog commands and queries, transport DTO translation, and the opt-in http4k Offerings contract routes. |
| `runtime/src/main/kotlin/io/github/castab/commerce/runtime/operation/` | Operation support: `CommerceFailure` and `validating`. |
| `runtime/src/main/kotlin/io/github/castab/commerce/runtime/http/` | `CommerceJson`, the error contract and filter, health routes, and `Authorization.kt` (the `authenticatedPrincipal` lens, `requirePermission`, `requireAuthenticatedPrincipal`, `AccessControl`). |
| `runtime/src/main/resources/` | Only the runtime's own Flyway migrations in `db/commerce/` (`V1` baseline, `V2` offerings tables, `V3` principal sessions, and `V4` authorization directory; see [Migration contract](#migration-contract)). No `application.conf` and no logging configuration. |
| `runtime/src/test/kotlin/io/github/castab/commerce/runtime/` | Kotest specs for configuration, errors, health, serialization, persistence and transactions, the migration contract (`persistence/MigrationLifecycleSpec`, `CommerceRuntimeStartupSpec`), and `CommerceRuntimeSpec` (the runtime composed with explicit contributions and an application-owned table, over real HTTP); `testing/TestDatabase.kt` and `testing/Databases.kt`. |
| `runtime/src/test/kotlin/io/github/castab/commerce/runtime/persistence/OfferingsSnapshotRepositorySpec.kt` | PostgreSQL round trips, revision rejection, and cross-schema atomicity. |
| `runtime/src/test/kotlin/io/github/castab/commerce/runtime/offering/OfferingsCapabilitySpec.kt` | Generic operation, real HTTP, historical revision, conflict, multiple catalog, read-only, price, and host OpenAPI composition checks. |
| `runtime/src/test/kotlin/io/github/castab/commerce/runtime/persistence/PrincipalSessionRepositorySpec.kt` | Session table and indexes, `UserId` and `ServiceId` round trips, digest-only storage and uniqueness, revocation, and caller-transaction atomicity. |
| `runtime/src/test/kotlin/io/github/castab/commerce/runtime/session/` | `SessionTokenSpec` (token generation, format, redaction, digest, activity), `SessionManagerSpec` (lifecycle on PostgreSQL with a hand-driven clock), and `SessionAuthenticationSpec` (401/403 behavior, current permissions, cookies). `testing/Sessions.kt` holds the test clock and output capture. |
| `runtime/src/test/kotlin/io/github/castab/commerce/runtime/authorization/` | PostgreSQL schema, live resolver, cross-schema transaction, session revocation, HTTP permission, and OpenAPI tests. |
| `runtime/src/test/resources/` | Test-only resources: a stand-in application `application.conf` (and a variant without the database block), `logback-test.xml`, the test application migrations in `db/testapp/`, `db/testapp-dependent/` (references a runtime-owned table), and `db/testapp-broken/` (fails), and the stand-in runtime stream in `db/testruntime/`. |
| `.github/workflows/ci.yml` | CI: lint, domain tests, runtime tests, and the full build on Java 25 for pull requests and pushes to `main`. |
| `.github/workflows/publish.yml` | Publish both artifacts to GitHub Packages when a GitHub Release is published. |
| `README.md`, `domain/README.md`, `runtime/README.md`, `AGENTS.md` | Documentation. Keep all of them in sync with the code. |

Maven coordinates: `io.github.castab:commerce-domain` and `io.github.castab:commerce-runtime`,
set by each module's publication `artifactId` (not by the Gradle project names), published
at one shared version to the GitHub Packages registry of the repository that runs the
Publish workflow. The repository is being renamed from `commerce-domain` to `commerce`, so
that registry is `https://maven.pkg.github.com/castab/commerce`.

# Booking lifecycle domain

The sections from here through [Anti-patterns](#anti-patterns) govern
`io.github.castab.commerce.booking.lifecycle`.

## Architectural invariants

These are non-negotiable without an explicit decision from the maintainer.

1. **The library owns the lifecycle type hierarchy.**
2. **Consuming applications own the concrete data types inhabiting that hierarchy.**
3. **Application models implement lifecycle phase interfaces directly.**
4. **Transition methods represent legal lifecycle edges.**
5. **Transition implementations belong to the adopting application.**
6. **Business prerequisites for transitions belong to the adopting application.**
7. **Invalid lifecycle edges should generally be absent from the type API rather than
   rejected at runtime.**
8. **Terminal lifecycle outcomes are immutable historical facts.**

The current topology, which code, KDoc, README, and tests must all agree on:

```text
InitialRequest ─ toQuote() ──→ Quote        InitialRequest ─ cancel() → Cancelled
Quote ────────── toBooking() → Booked       Quote ────────── cancel() → Cancelled
Booked ───────── complete() ─→ Completed    Booked ───────── cancel() → Cancelled
Cancelled, Completed: no transitions
Entry points: InitialRequest, Quote
```

## Sealed vs open interfaces

The shape is deliberate:

```text
BookingLifecycle          sealed interface
├── Active                sealed interface
│   ├── InitialRequest    interface (open)
│   ├── Quote             interface (open)
│   └── Booked            interface (open)
└── Terminal              sealed interface
    ├── Cancelled         interface (open)
    └── Completed         interface (open)
```

- **The sealed hierarchy controls the broad lifecycle taxonomy.** Only this module can
  declare direct subtypes of `BookingLifecycle`, `Active`, and `Terminal`, so no adopter
  can invent a sixth phase. `when` over a `BookingLifecycle` or an `Active` is exhaustive
  without `else`, and the tests rely on that (`classify` and `describe` in
  `BookingLifecycleSpec`). Do not remove sealing from these three without understanding
  and replacing what it provides.
- **The concrete phase interfaces stay open so that external application models can
  implement them.** Kotlin restricts only *direct* subtypes of a sealed type to the
  declaring module. Do not make `InitialRequest`, `Quote`, `Booked`, `Cancelled`, or
  `Completed` sealed, or otherwise prevent adopters in other modules from implementing
  them. That would destroy the library's purpose.

Known limit: the type system does not stop one class from implementing two phases (for
example `Quote` and `Booked`). Phases are intended to be mutually exclusive, and
documentation tells adopters to implement exactly one. Don't add runtime checks for this.
If enforcement is ever wanted, treat it as an architectural decision.

## Transition design

**Transition methods are the state machine.** The legal graph is encoded entirely by
which abstract functions each phase interface declares.

- Do not introduce a generic `StateMachine`, `TransitionEngine`, `TransitionValidator`,
  `transition(...)`, `canTransitionTo(...)`, or `allowedTransitions(...)` that merely
  restates what the interface graph already encodes. Add a runtime transition mechanism
  only if a future requirement gives it value beyond the compile-time topology, and only
  after an architectural decision.
- Every transition function stays **abstract**. Never add a default implementation. The
  library knows `Quote → Booked` is legal. It cannot know how an adopter's quote becomes
  that adopter's booking.
- Transition return types are the protocol types (`Booked`, `Terminal.Cancelled`, ...).
  Adopters narrow them with **covariant overrides** (`override fun toBooking():
  CateringBooking`). Preserve this. Do not add generics, self-type parameters, or wrapper
  return types (`Result<Booked>`, `Transition<Booked>`) that break or complicate it.
- Every active phase declares its own `cancel()`. There is intentionally no shared
  `cancel()` on `Active`. See [Open questions](#open-questions).

## Transition policy boundary

Distinguish a **legal transition** from a **business-authorized transition**.

- `Quote → Booked` is *legal* according to this library.
- Whether a particular quote may become booked, because a deposit was paid, a contract
  was signed, or a manager approved, is *business authorization*, and the adopter owns it.

Do not add generic payment, approval, acceptance, availability, actor, or timestamp
concepts to the core library to solve an application-specific requirement. Do not add
`TransitionContext`, `TransitionPolicy`, `TransitionCondition`, or `TransitionGuard`.
Adopters express policy inside their own transition implementations, or before calling
them.

## Application data boundary

Booking lifecycle interfaces declare transitions only, with no properties and no data.
The library has no booking record, booking ID, customer, contact, or location type; see
[Booking boundary](#booking-boundary).

Avoid introducing types or fields such as these into the booking lifecycle:

```text
Customer  Money  Invoice  QuoteData  Payment  Deposit  Actor  Event  Selection
Complaint  Refund  CancellationReason  bookingId  createdAt  version
```

Add one only if a future architectural decision proves it is a lifecycle concept rather
than adopter data. `Money` and `Invoice` exist in the financial package, and payments and
refunds in the payment package. That does not make them booking lifecycle concepts:
never reference financial or payment types from `BookingLifecycle`. Booking identity (how
a quote and its booking are known to be the same booking) is application-owned; see
[Open questions](#open-questions).

## New lifecycle phase checklist

Before adding a lifecycle phase or changing an edge, answer all of these:

1. Does this represent a genuinely distinct phase of the booking lifecycle?
2. Is it mutually exclusive with existing lifecycle phases?
3. Does it alter the legal transition graph?
4. Would unrelated booking applications recognize this phase?
5. Can a booking meaningfully *remain* in this phase?
6. Is this actually a payment, support, accounting, fulfillment, quote-revision, or
   invoice concern?
7. Could this concept instead be modeled by an application-owned type or an orthogonal
   state machine?

If question 6 or 7 suggests another domain owns the concept, do **not** add it to
`BookingLifecycle`. `DepositPaid`, `InvoiceSent`, `QuoteRevised`, `Refunded`, and
`Disputed` all fail this checklist.

## Terminal phase rules

`Cancelled` and `Completed` are terminal historical outcomes. `Cancelled` means the
lifecycle ended without fulfillment. `Completed` means the booked service or event was
fulfilled.

- Do not add outgoing lifecycle transitions (`reopen()`, `refund()`, `revert()`, ...) to
  terminal interfaces without an explicit redesign of lifecycle semantics.
- Do not add dummy members to terminal interfaces for symmetry. The "protocol shape"
  tests assert that terminal interfaces declare no methods.
- Post-completion activity such as a refund, complaint, chargeback, credit, or
  corrective service does not reopen or rewrite the booking lifecycle. It belongs to
  application-owned processes that *reference* the booking. `Completed` plus a refund and
  `Cancelled` plus a refund are different historical facts, and both remain expressible
  without new lifecycle phases.

## Anti-patterns

**State-property replacement.** Do not replace the type-level protocol with:

```kotlin
enum class BookingState { INITIAL_REQUEST, QUOTE, BOOKED, CANCELLED, COMPLETED }

data class Booking(val state: BookingState /* ... */)
```

**Mutable lifecycle state.** Do not model progression as `var state: BookingState`. The
intended model transforms one lifecycle-typed application model into another.

**Universal transition engine.** Do not make `transition(from, to)` the primary lifecycle
API when interface methods already express legal transitions.

**Application data in lifecycle interfaces.**

```kotlin
interface Quote : Active {
    val customer: Customer   // wrong: adopter data
    val total: Money         // wrong: adopter data
}
```

**Financial or support states as booking phases.** `Refunded`, `PartiallyRefunded`,
`DepositPaid`, `Chargeback`, `Disputed`, `ComplaintOpened`, `CompletedRefunded`.

**Revisions as phases.** `QuoteRevised`, `InvoiceSent`, `InvoiceRevised`.

**Runtime emulation of illegal edges.** Do not add `fun complete(): Nothing = throw ...`
to `InitialRequest`, or similar. An illegal edge must have no method at all.

# Financial document domain

## Relationship boundary

A financial document carries no customer, booking, inquiry, or other application
reference. See [Independent financial documents](#independent-financial-documents).
Payment and refund records do not reference customers either.

These rules govern `io.github.castab.commerce.financial`. A `FinancialDocument` is one
immutable snapshot of a commercial document. The domain describes what is charged and how
that description evolves. It does not describe settlement.

## Financial invariants

These are non-negotiable without an explicit decision from the maintainer.

1. **The stage is the sealed subtype.** `FinancialDocument` is a sealed class with exactly
   `Estimate`, `Quote`, and `Invoice`. There is no stage enum or mutable stage/type
   property driving behavior. `when` over a document is exhaustive.
2. **Snapshots are immutable.** Nothing modifies a snapshot. Every change order and every
   transition returns a new snapshot.
3. **A lineage keeps one `UUID`.** Successors share the source's `id`, are at
   `version.next()`, and have `previousVersion == source.version`. A lineage starts at
   `Version.INITIAL` with `previousVersion == null`. Consequently `previousVersion` is
   always the version immediately preceding `version`, and the base class checks this.
4. **Lineages may start at any stage.** `Estimate.create`, `Quote.create`, and
   `Invoice.create` are all first-class entry points. Direct invoice creation (point of
   sale) is not a workaround.
5. **Transitions move one stage forward.** `Estimate.toQuote()` and `Quote.toInvoice()`
   only. No `Estimate.toInvoice()`, no reverse transitions, and nothing leaves `Invoice`
   except its own change orders. Illegal transitions have no method.
6. **A change order never changes the stage.** Each stage's `changeOrder` returns its own
   type.
7. **Change orders are ordered and atomic.** Changes apply in list order to a working copy.
   The successor is constructed only after every change succeeds, so a failure yields no
   snapshot at all.
8. **Totals are derived.** `subtotal`, `taxAmount`, and `total` are calculated from line
   items. No API accepts them.
9. **One currency per document.** `Money` never converts or rounds. Mixed currencies are
   rejected in `Money` arithmetic, within a `LineItem`, and within a document.
10. **History is referenced, never embedded.** A snapshot holds no other snapshot and no
    reference object to one. History is reached only through `FinancialDocumentHistory`,
    one explicit lookup at a time.
11. **A document is an independent fact.** Its state is its identity, version lineage,
    stage, line items, currency, and derived totals, nothing else. `FinancialDocumentSpec`
    asserts this public shape by reflection; update it deliberately and never add an
    ownership or relationship field to make a change pass.

The topology, which code, KDoc, README, and tests must all agree on:

```text
Estimate ─ changeOrder() → Estimate     Estimate ─ toQuote() ──→ Quote
Quote ──── changeOrder() → Quote        Quote ──── toInvoice() → Invoice
Invoice ── changeOrder() → Invoice
Entry points: Estimate.create, Quote.create, Invoice.create
```

## Construction and forgery

- Stage constructors are **private**. Do not make them `internal`, `protected`, or public:
  `internal` constructors are public in bytecode and callable from Java.
- Cross-stage successors are built through `@JvmSynthetic internal` companion functions
  (`Quote.successorOf`, `Invoice.successorOf`), invisible to Java and to other modules.
- The stages are **not** data classes. A `copy()` would let callers forge versions,
  previous-version links, or stages. Equality, `hashCode`, and `toString` are implemented
  once, finally, on `FinancialDocument`.
- `restore(id, version, lineItems)` exists on each stage only so persistence adapters and
  `FinancialDocumentHistory` implementations can rebuild stored snapshots. It derives
  `previousVersion` from `version` and cannot express any other link. Do not add
  parameters that let callers choose `previousVersion`, totals, or anything else derived.
- `Version` has a private constructor. `Version.of(n)` rejects `n < 1`. Don't add public
  arithmetic beyond `next()`.

## Values and collections

- Every financial identifier is a `java.util.UUID`. Do not add id wrapper types
  (`FinancialDocumentId`, `LineItemId`, ...). Do not add an id to `ChangeOrder` for
  symmetry. `Version` is a domain value, not an identifier.
- Money is `BigDecimal` plus `java.util.Currency`. Never `Double` or `Float`. Don't add
  rounding, scale normalization, currency conversion, or exchange rates.
- `LineItem.quantity == null` means flat-priced (subtotal = price). Otherwise
  subtotal = price × quantity. `price` excludes tax, and `taxAmount` is the final tax for
  the line. Do not rename it to `taxableAmount`, and do not turn it into a rate.
- `LineItem`, `Money`, `FinancialDocumentReference`, and the `ChangeOrder.Change` types are
  data classes, because `copy()` on them cannot break an invariant: every copy re-runs
  validation. Keep validation in `init` blocks so this stays true.
- Collections received from callers are copied into unmodifiable lists
  (`toImmutableList()`), so neither the caller's list nor a cast to `MutableList` can change
  a snapshot or a change order.
- Change orders replace whole line items. Do not add field-level patch semantics or
  nullable "unchanged" markers.

## Persistence and concurrency boundary

- `FinancialDocumentHistory` is the only history access point. It is an SPI implemented by
  applications (or by `:runtime`). `commerce-domain` must never ship an implementation
  tied to a database; a PostgreSQL implementation belongs in `:runtime`. No
  implementation may load previous versions implicitly or recursively.
- `retrievePreviousVersion` performs zero lookups for version 1 and exactly one lookup
  otherwise. `retrieveVersion` performs exactly one lookup. `retrieveLatestVersion`
  delegates by `id`. Keep these guarantees, and the tests that verify them with MockK.
- Do not add locks, transactions, global registries, caches, or static mutable state.
  Concurrent successors of the same snapshot are resolved by the persistence layer's
  uniqueness or optimistic-concurrency check on `(id, version)`.

## Out of scope for financial documents

A financial document does not own settlement state. Never add payment or settlement
concepts to any financial type: `amountPaid`, `amountRefunded`, `balance`, `balanceDue`,
`remainingBalance`, `paymentStatus`, `payments`, `refunds`, `paymentMethod`,
`paymentIntent(Id)`, `transactionId`, `refundAmount`, `paidAt`, `partiallyPaid`,
`overdue`, payment history, payment processors (Stripe, Square, PayPal), or accounting
ledgers. Settlement is a separate bounded context, modeled by the
[payment reconciliation domain](#payment-reconciliation-domain), which references a
document by `FinancialDocumentReference`. The financial package never imports it.

Also out of scope: pricing rules, tax calculation, discount engines, customers, customer
PII, counterparty or recipient models, dates and due dates, document numbering, and
serialization annotations. These are application data unless an architectural decision says otherwise.

## Financial anti-patterns

```kotlin
enum class FinancialDocumentType { ESTIMATE, QUOTE, INVOICE }   // wrong: the subtype is the stage
data class FinancialDocument(val type: FinancialDocumentType, ...)

class Invoice(...) { var version: Version }                     // wrong: snapshots are immutable
class Quote(val previous: Quote?)                               // wrong: embeds history recursively
fun Estimate.toInvoice(): Invoice                               // wrong: an illegal edge
Invoice.create(id, lineItems, total = ...)                      // wrong: totals are derived
val balanceDue: Money                                           // wrong: settlement is derived in the payment domain
```

# Payment reconciliation domain

These rules govern `io.github.castab.commerce.payment`. The domain records money received
and returned, where received money was applied, and corrections to that, and derives
reconciliation from those records. It answers "what did we receive, where was it applied,
what was corrected, what was returned, and what is the balance?". It does not answer
"which ledger accounts were debited?".

The sibling `payment.adapter` package is the deliberate provider boundary. Its
`PreparePayment`, `PaymentPrepared`, `RequestRefund`, observations, and optional checkout
URI do not alter a payment or refund record. `AuthorizedPayment` represents the consuming application's
already-approved amount, including for observation-only providers. The adapter package
may describe a provider operation without turning pending activity into a money-movement
record. The no-checkout rule below continues to apply to core payment records and
reconciliation; only an optional provider-hosted navigation URI belongs in this adapter
contract.

Adapter implementations authenticate provider input and translate it. This library does
not define a transport, SDK, signature check, database, or provider-specific status.
`PaymentProviderId` is extensible; existing `ExternalPaymentReference` and
`ExternalRefundReference` remain the provider object references. Their provider strings
must match `PaymentProviderId.value` when used in this contract. A
`ProviderEventReference` instead identifies one event by `(provider, eventId)`; one
provider object may produce many events. Receipts hold minimal metadata, never raw
provider payloads. A consuming application must persist an accepted receipt and its
corresponding payment/refund fact or request-status effect atomically, with uniqueness on
the event pair, application-owned record ID, and provider object reference. The pure processing
functions receive application-supplied records and receipts and cannot enforce storage
uniqueness or transaction isolation themselves.

Provider success must match the consuming application's authorized identity, currency, and numeric
amount. Refund success must match the requested refund and stay within the payment's
remaining refundable amount. Failures create no payment or refund money-movement record.
An already completed payment or refund cannot be undone by a failure observation.
Capability checks apply before outbound initiation or refund requests; they do not
invalidate an authenticated success observation if the adapter's advertised capabilities
later change. Keep this boundary provider-neutral and transport-neutral.

The separation of concepts, which code, KDoc, README, and tests must all agree on:

```text
FinancialDocument           what is being charged                 (financial package)
PaymentRecord               money received
PaymentAllocation           received money applied to one document snapshot
PaymentAllocationReversal   an erroneous allocation corrected     (no money moves)
RefundRecord                money returned to the payer           (references a payment)
RefundAllocation            which allocation a refund unwinds     (optional)
PaymentReconciliation, FinancialDocumentReconciliation   derived, never stored
```

## Payment invariants

These are non-negotiable without an explicit decision from the maintainer.

1. **Records are immutable facts.** Nothing edits or deletes a record. There are no
   mutable properties and no `copy()` on records.
2. **Corrections are appended.** A wrong allocation is corrected by a
   `PaymentAllocationReversal`, money returned by a `RefundRecord` (plus a
   `RefundAllocation` when it unwinds applied value). History keeps every record.
3. **Money movement and reconciliation are separate.** A `PaymentRecord` is the only record
   of money arriving and a `RefundRecord` the only record of money leaving. Allocations,
   reversals, and refund allocations move no money.
4. **A reversal is never a refund, and a refund is never a reversal.** Never implement one
   with the other, and never implement a refund by changing or deleting an allocation.
   They also differ in what they leave allocatable: a reversal moves no money, so the
   reversed amount becomes unapplied and can be allocated again. A refund reduces
   `netReceived`, and its `RefundAllocation` only identifies which applied value the refund
   unwound; refunded money never becomes available to allocate again. Never document or
   implement a refund allocation as freeing value.
5. **A payment belongs to no document.** `PaymentRecord` has no document reference,
   allocation, balance, or refunded amount.
6. **An allocation references an exact snapshot.** `PaymentAllocation.financialDocumentReference`
   is the `(id, version)` the money was applied against. Its `id` alone identifies the
   lineage. Never add a second, lineage-only document id. Allocations never roll forward
   when a document advances.
7. **A refund references a payment, not a document.** Its link to applied value is an
   optional `RefundAllocation`, which must reference an allocation of the same payment.
8. **Relationships are references.** Records hold `UUID`s and `FinancialDocumentReference`s,
   never a `FinancialDocument`, `PaymentRecord`, `RefundRecord`, or another record.
9. **Settlement is derived.** Gross and net allocated, refunded totals, unallocated amounts,
   and balances exist only on the reconciliation results, recalculated from records. Never
   store them on a record or a document. There is no stored or mutable payment status.
10. **Amounts are strictly positive** (compared numerically, so `0.00` is rejected), in one
    currency per payment. `Money` never converts.
11. **Identifiers are caller-supplied `UUID`s.** The library never generates ids.

## Creation, restoration, and validation

- Records whose creation must agree with other records (`PaymentAllocation`,
  `PaymentAllocationReversal`, `RefundRecord`, `RefundAllocation`) have **private**
  constructors, a `create` that takes the real objects and checks currency, same-payment
  links, and single-record limits, and a `restore` that takes references for persistence
  adapters. Stored records hold only references either way. `PaymentRecord` depends on no
  other record and has a public constructor.
- Checks that need several records (cumulative reversals and refund allocations per
  allocation, cumulative refunds per payment, cumulative refund allocations per refund,
  over-allocation of a payment, allocations to a later version than the reconciled
  snapshot, repeated ids) belong in `PaymentReconciliation` and
  `FinancialDocumentReconciliation`, never in a single record.
- Reconciliation validates the supplied records as a whole and does not interpret the order
  of timestamps. Don't add chronological rules without an architectural decision.
- Reconciliation takes collections the application supplies and loads nothing. Records of
  other payments or documents in those collections are ignored. Don't add a repository,
  history SPI, or lookup to this package incidentally.
- Invalid or inconsistent input fails with `require` (`IllegalArgumentException`) and a
  message naming the records involved.
- `PaymentMethod` describes the instrument, never the processor. Processors appear only as
  opaque `ExternalPaymentReference` / `ExternalRefundReference` strings. Keep the two
  reference types separate.

## Payment policy boundary

The library records what happened. Never encode business, processor, or regulatory
policy: which stages may accept money, deposit percentages, refund windows, refunds to the
original method (`refund.method` may differ from `payment.method`), approval, or who may
issue a refund.

## Out of scope for payments

Do not add, without an architectural decision: store or customer credit, gift cards,
credit memos, chargebacks, disputes, authorization and capture, processor fees, tips,
payouts, settlement batches, bank reconciliation, double-entry accounting (accounts,
journals, debits, credits, posting periods), tax accounting, foreign exchange, processor
SDKs, card data, stored cards, ACH workflows, payment links, checkout sessions, status
polling, persistence, or serialization. A card payment converted into store credit is
not a `RefundRecord`, because no money left the business.

## Payment anti-patterns

```kotlin
class PaymentRecord(val invoiceId: UUID, ...)                   // wrong: a payment belongs to no document
class PaymentAllocation(val payment: PaymentRecord, ...)        // wrong: reference, never embed
class PaymentAllocation(var financialDocumentReference: ...)    // wrong: allocations never roll forward
class PaymentAllocation(val documentId: UUID, ...)              // wrong: the reference already carries the lineage id
require(refund.method == payment.method)                        // wrong: the refund method is a fact, not a policy
fun reverse(a: PaymentAllocation): RefundRecord                 // wrong: a reversal is not a refund
enum class PaymentMethod { STRIPE, PAYPAL }                     // wrong: a processor is not a method
val PaymentRecord.status: PaymentStatus                         // wrong: status is derived by the application
```

# Principal authorization domain

These rules govern `io.github.castab.commerce.staff`. A `User` is a human staff member;
`ServiceIdentity` is a non-human software caller. Both implement `Principal`, with a
shared `PrincipalStatus` and role assignments. Their UUID-backed `UserId` and
`ServiceId` are distinct `PrincipalId` types. The package is independent of the booking
lifecycle, financial, and payment packages. Neither principal holds passwords, API keys,
tokens, certificates, or other authentication data. Applications authenticate callers
before supplying a `PrincipalId` to authorization. `UserStatus` remains a Kotlin alias
for `PrincipalStatus` for source compatibility.

- `RoleKey` and `PermissionKey` are open-ended values, never enums. Commerce-defined
  role keys are conventions, not hard-coded grants. Applications or runtime persistence supply role definitions.
- Operations ordinarily check permissions, not role names or principal types.
  `PrincipalId.can` takes an explicit `PermissionResolver`; do not hide resolver state
  in a singleton or locator.
- `PrincipalResolver` and `RoleResolver` are ports implemented by the runtime directory or another consumer.
  `UserResolver` remains a human-specific port, but authorization uses
  `PrincipalResolver`. The standard `RoleBasedPermissionResolver` unions grants from
  matching resolved role definitions. It returns no permissions for missing or disabled
  principals or mismatched identities, and skips missing or mismatched role definitions.
  A service has no implicit trust bypass. No explicit deny or role precedence exists.
- Role assignments and definitions contain no scope in this version. Do not introduce
  location scoping, a policy engine, or authentication/session logic incidentally.
- Future actor attribution may refer to `PrincipalId`, preserving whether a human or
  service performed the action. Existing commerce records do not gain actor fields as
  part of this domain.

# Offerings domain

`io.github.castab.commerce.offering` is a reusable vocabulary for commercial choices.
An `Offering` is an available item, service, or choice in exactly one `OfferingCategory`.
The category groups offerings and expresses only generic selection cardinality through a
minimum and optional maximum. Machine keys are distinct from presentation text.

`OfferingsSnapshot` is an immutable, ordered catalog revision. It has a stable
`OfferingsCatalogId`, an independent `OfferingsRevision`, and an immediate predecessor
reference. `OfferingsSnapshotReference` identifies exactly one revision. Snapshots enforce
unique category and offering keys, valid category references, and revision sequence.
Empty catalogs are permitted. An application may change its catalog by storing a new
snapshot; prior revisions remain historical facts.

`OfferingPrice` has only `Fixed`, `PerQuantity` with an application-named
`QuantityDimension`, and `PerDuration` with a positive `Duration`. It is descriptive
price metadata, not a pricing rules system. Do not add business-specific rates such as
`PER_GUEST`, metadata maps, or a general expression/rule DSL. A new common pricing
primitive needs evidence from more than one concrete business. A direct price may be
absent.

`OfferingSelections` are ordered candidate choices. `OfferingsEngine<C>` first checks
snapshot-dependent structural validity (known categories and offerings, category
membership, min/max cardinality, duplicate blocks and offerings). Only then does it call
the application's policy implementation. The open `OfferingsViolation` interface permits
application-defined rejection codes. Applications own contexts, pricing calculations,
bundles, dependencies, and availability policy.

An accepted `OfferingsEvaluation` records the exact snapshot reference, submitted
selections, and one or more generic financial `LineItem`s. The application may give these
lines directly to `FinancialDocument.Estimate.create`. The engine does not create or
persist that estimate. Offerings describe what may be selected; financial documents
record the resulting commercial fact. Neither concept owns the other.

Runtime persistence is append-only in the `commerce` schema. The
`OfferingsSnapshotRepository` takes the caller's `Transaction` first and uses
`transaction.handle` for inserts and reads. The `CommerceRuntimeContext` exposes it.
It never starts a transaction or creates a separate pool. Category and offering order and each price subtype round trip
through explicit relational columns. Cross-schema transaction tests cover commits and
rollbacks with application-owned rows.

## Runtime Offerings capability

`commerce-runtime` owns generic catalog commands and queries in `runtime.offering`.
Adopters supply explicit catalog IDs; they do not need to reimplement generic catalog
administration. Commands own `Transactor` boundaries, derive immediate immutable
successors, and call the append-only `OfferingsSnapshotRepository`. Concurrent successor
collisions surface as `CommerceFailure.Conflict` without automatic retry or merge. Do
not add update/delete repository methods or generic update/delete HTTP semantics; later
changes need deliberately designed successor-revision commands.

The Offerings HTTP capability is explicitly mounted and bound to one application-supplied
catalog ID and base path. Its runtime-owned serializable DTOs translate domain values;
domain types stay serialization-free. The original http4k contract routes are the single
source for execution and host OpenAPI metadata. The runtime does not own the host's
aggregate OpenAPI document, Swagger UI, or route mount. `ReadOnly` exposes the
reads only and needs no authorization dependency. `ReadWrite(accessControl)` adds the writes,
each requiring `CommercePermissions.OfferingsManage` through the application's
`AccessControl` (`401` without a principal, `403` without the permission). Reads keep no
permission requirement; the host decides where to mount them.
Hosts rendering these routes with http4k OpenAPI use `offeringsOpenApiRenderer` so the
shared `OfferingPriceDto` definition is the three-branch `kind`-discriminated `oneOf`.
Application `OfferingsEngine` policy remains outside generic catalog HTTP. Released
runtime migrations, including `V2__offerings_snapshots.sql`, remain immutable.

# Repository-wide rules

These sections apply to every domain and to the build.


## Persistence boundary

Persistence exists only in `:runtime`. `:domain` has none: `FinancialDocumentHistory` is
an SPI that applications (or `:runtime`) implement, not a persistence layer. Do not add
persistence to `:domain` for any reason.

In `:runtime`:

- PostgreSQL through HikariCP and JDBI, with Flyway migrations. No ORM.
- Commerce-owned tables live in the `commerce` schema. Migrations follow the
  [Migration contract](#migration-contract).
- SQL always names the `commerce` schema explicitly. Do not rely on `search_path`: its
  `"$user"` entry resolves to `commerce` when the role is named `commerce`.
- Migrations are append-only. Never edit a migration that has been released.
- Do not add SQL, document, or ORM concerns (annotations, surrogate keys, column names,
  optimistic-lock columns) to domain types. The financial `UUID` id and `Version` are
  domain concepts, not persistence concerns, and stay as they are. Map rows to domain
  values explicitly in repositories, restoring them through the domain's own
  constructors and `restore` factories.
- Do not force adopter business models into library-owned persistence models.
- Repositories take the caller's `Transaction` and never begin, commit, or roll back.
  Transaction boundaries belong to operations, through `Transactor`. Do
  not add repositories whose every call is an unrelated transaction.
- Do not introduce a generic `Repository<T, ID>` or repository framework. Use
  intention-revealing repositories, and implement a domain SPI (such as
  `FinancialDocumentHistory`) where the domain already defines the boundary.

## Migration contract

**The runtime owns migration orchestration. Each participant owns its own migrations.** A
runtime version and the database shape it requires are one compatibility unit.

```text
MigrationLifecycle.migrate():
    runtime stream        validate → migrate   classpath:db/commerce    commerce.flyway_schema_history
        ↓ (only if it succeeded)
    application stream    validate → migrate   contributed locations    public.flyway_schema_history
        ↓
commerceRuntime(...) composes Jdbi, repositories, routes, Jetty    →    start() serves HTTP
```

- **Ownership.** `commerce-runtime` owns the `commerce` schema and every object in it. The
  concrete application owns its schemas and objects. Sharing a database is not shared
  ownership. A runtime migration creates, alters, or drops runtime-owned objects only and
  never touches an application-owned table, index, constraint, sequence, view, or other
  object. Application migrations never alter runtime-owned DDL. They may remove stale
  application-defined grants from `commerce.role_permissions` before startup catalog
  validation. This narrow data cleanup does not grant schema ownership. The boundary is
  enforced by review, not SQL analysis: do not add SQL parsing or schema policing.
- **Discovery.** `RuntimeMigrations` discovers the runtime's migrations internally, at
  `classpath:db/commerce` in the runtime jar. Applications never list that location;
  `ApplicationContributions.migrationLocations` names application migrations only, and a
  location that overlaps `db/commerce` (including an ancestor such as `classpath:db`) is
  rejected. Never put application migrations under `db/commerce`, or runtime migrations
  anywhere else.
- **Independent streams.** Runtime and application migrations are two Flyway streams with
  separate schema histories and version spaces. An application's `V1` coexists with the
  runtime's `V1`. Never merge them into one interleaved sequence or one history.
- **Ordering is explicit code.** `MigrationLifecycle.migrate()` runs the runtime stream,
  then the application stream. Never rely on classpath, filesystem, or Flyway location
  order to sequence them. Application migrations may therefore reference runtime-owned
  objects created in the same run.
- **Startup gating.** `commerceRuntime(...)` runs the migration phase before it constructs
  anything else: `migrations.onStartup = MIGRATE` applies both streams, and `VALIDATE`
  (the default) applies nothing and fails unless both streams are fully applied. Any
  failure throws Flyway's own exception (never wrapped into a vague one) and no
  `CommerceRuntime` is returned, so no server can start against an incompatible database.
  A runtime-stream failure means the application stream is never attempted.
- **Separable from HTTP.** `MigrationLifecycle(dataSource, locations).migrate()` runs
  the phase without composing the runtime or starting Jetty, for a release or migration
  step that precedes deployment. Do not add a CLI or deployment tooling for it
  incidentally.
- **Concurrency.** Several instances may migrate at once. Flyway serializes each stream
  with a PostgreSQL advisory lock keyed by its history table, and validates before
  migrating. Keep Flyway's defaults: never disable `validateOnMigrate`, enable
  `outOfOrder`, `baselineOnMigrate`, or `cleanDisabled(false)`, turn off the PostgreSQL
  lock, or add a custom lock or leader election.
- **Published database contract.** Runtime-owned structures an application may reference
  (for example a foreign key to the key of a `commerce` table) are a compatibility surface, as
  public as a Kotlin API. Runtime migrations must preserve the published database
  contract across compatible runtime releases. Destructive changes require an explicit
  compatibility transition: prefer **expand** (add the new structure, keep the old),
  **migrate** (move runtime and consumers to it), then **contract** (remove the old
  structure only once compatibility has been deliberately ended). Never drop or
  incompatibly alter a published structure in a single release. `VALIDATE` accepts
  migrations newer than the running release, so older instances keep starting after an
  expand step.
- **History is immutable.** Released versioned migrations are historical records. Correct
  one with a new migration, never by editing it. Commerce-runtime 0.0.4 and 0.0.5 shipped a runtime migration that created `commerce.customers` and one
  that dropped it. Before any real consumer existed, the maintainer collapsed that history
  into `V1__commerce_baseline.sql`, a one-time pre-release reset: databases migrated by
  those releases fail validation and must be recreated.

## Dependency policy

**`:domain`.** The published dependency surface is `kotlin-stdlib` only. Keep it that way.
`verifyRuntimeDependencies` (part of `:domain:check`) fails the build if anything else
reaches the domain's runtime classpath. Never weaken or bypass that task.

Do not add these to `:domain`:

```text
Spring  Ktor  Hibernate  JPA  JDBI  HikariCP  PostgreSQL  Flyway  Hoplite  http4k  Jetty
MongoDB drivers  Jackson  kotlinx.serialization  Logback  kotlin-logging  NATS  Kafka
```

**`:runtime`.** The runtime stack is fixed: http4k (core, Jetty server, kotlinx-serialization
format), kotlinx.serialization, HikariCP, JDBI, the PostgreSQL driver, Flyway, Hoplite
(HOCON), and Kotlin Logging on the SLF4J API. No SLF4J provider is part of the published
stack; Logback is a `testRuntimeOnly` dependency of the runtime's own tests, and the
published POM and module metadata must never select a provider (check with
`./gradlew :runtime:dependencies --configuration runtimeClasspath`). A library whose
types appear in the runtime's public
API is an `api` dependency; everything else is `implementation` or `runtimeOnly`. The
current `api` set (http4k core and its kotlinx-serialization format, kotlinx.serialization,
JDBI, HikariCP) is deliberate but revisitable. Do not grow it casually; see
[Provisional application-extension seam](#provisional-application-extension-seam). Never
add Spring, Spring Boot, Hibernate, JPA, Micronaut, Quarkus, Ktor, a dependency-injection
framework, or a payment-provider SDK (Stripe or any other).

Versions for both modules live in `gradle/libs.versions.toml`. Runtime versions follow the
reference backend (`castab/fionas-ui` `apps/backend`); check its current `main` before
upgrading them.

Test-only dependencies (Kotest, MockK) belong in `testImplementation` and must never leak
into the runtime or API dependencies. Check with:

```bash
./gradlew :domain:dependencies --configuration runtimeClasspath
./gradlew :runtime:dependencies --configuration runtimeClasspath
```

No preview, EAP, milestone, RC, snapshot, or nightly dependencies. Never add a runtime
dependency just to support CI or publishing.

## Toolchain rules

- **Java 25 is a hard requirement of both modules.** The Java toolchain, Kotlin
  `jvmTarget`, and `JavaCompile.release` are all 25 (configured once in the root
  `build.gradle.kts`), and tests run on the Java 25 toolchain. Toolchain auto-download is
  disabled (`gradle.properties`), and no foojay resolver is applied, so a missing Java 25
  fails the build. Do not lower any of these to accommodate a tool or a consumer.
- Versions: Kotlin 2.4.20, Kotest 6.2.5, MockK 1.14.11 (`gradle/libs.versions.toml`).
  The Gradle wrapper is 9.7.0, the newest Gradle that Kotlin 2.4.20 declares full
  compatibility with, and its distribution checksum is pinned. Do not bump Gradle beyond
  what the Kotlin Gradle plugin officially supports.
- MockK 1.14.11 currently resolves Byte Buddy 1.18.2 and runs on Java 25 without an
  override. If a future upgrade breaks MockK on Java 25 because of Byte Buddy, add a
  **test-scoped** constraint for `net.bytebuddy:byte-buddy` and
  `net.bytebuddy:byte-buddy-agent`, with a comment in the build file explaining why. Do not
  replace MockK and do not lower Java.

## CI and publication

- **Java 25 in CI.** Both workflows use Temurin 25 through `actions/setup-java`. Do not
  add a matrix with older JDKs or lower the version to get CI green.
- **The Gradle Wrapper is authoritative.** Workflows run `./gradlew`, and
  `gradle/actions/setup-gradle` provides caching and wrapper validation. Do not install
  another Gradle, and do not add competing cache steps.
- **CI must pass before publication.** `ci.yml` runs, with `contents: read` only,
  `ktlintCheck`, `:domain:test`, `:runtime:test`, and `build` (each with
  `--no-build-cache`). It can never publish. `publish.yml` runs `./gradlew clean build
  --no-build-cache` and publishes only if it succeeds. Never add `continue-on-error`,
  skip tests, or reorder these steps. One workflow verifies both modules; do not split it
  into per-module workflows.
- **Runtime tests need Docker.** `:runtime:test` starts PostgreSQL through the runner's
  Docker CLI (a Gradle build service in `runtime/build.gradle.kts`). Do not replace it
  with Testcontainers, H2, or an embedded database, and do not skip database specs to get
  CI green.
- **Releases go to GitHub Packages, triggered only by a published GitHub Release.** Do not
  publish from pushes, pull requests, or schedules. `publish.yml` is the only workflow
  with `packages: write`. Do not grant `contents: write`, `id-token: write`, or other
  scopes unless a new requirement truly needs them.
- **Maven versions derive from release tags.** A tag `vX.Y.Z[-prerelease]` becomes Maven
  version `X.Y.Z[-prerelease]` for **both** artifacts, passed to Gradle as the `version`
  project property (`ORG_GRADLE_PROJECT_version`). The modules always share one version,
  and `commerce-runtime`'s POM depends on `commerce-domain` at that version. The build script's default, `0.0.0-SNAPSHOT`, is for
  local builds. Never write release versions into `build.gradle.kts`,
  `gradle.properties`, or the workflow files.
- **Published versions are immutable.** Never design for overwriting a released version.
  A fix is released as the next version.
- **Credentials are never committed.** Publishing uses the workflow's `GITHUB_TOKEN`,
  passed as the `GitHubPackagesUsername` and `GitHubPackagesPassword` Gradle properties
  through `credentials(PasswordCredentials::class)`. Local `build`, `test`, and
  `publishToMavenLocal` must keep working without any GitHub credentials. Never put
  tokens in repository files. Consumers keep theirs in `~/.gradle/gradle.properties`.
- **Artifact identity is not project identity.** The Gradle projects are `:domain` and
  `:runtime`; their publications set `artifactId` to `commerce-domain` and
  `commerce-runtime` (and `archivesName` to match). Never publish an artifact named
  `domain` or `runtime`, and never change `io.github.castab:commerce-domain`. The
  pre-release `commerce-service` artifact name was replaced by `commerce-runtime`; do not
  reintroduce it or publish a compatibility artifact under it.
- **Published artifacts, per module:** the main jar, a sources jar, a javadoc jar (empty
  for now, because the sources are Kotlin-only and Dokka is not used), the POM, and
  Gradle module metadata. The POMs declare the repository's license (Apache-2.0, see
  `LICENSE`). Keep the two in sync. `commerce-runtime` publishes a plain library jar;
  deployable fat jars and images belong to consuming applications.
- **Test-only dependencies must not leak into the published library.** Check the
  generated POM or `runtimeClasspath` after dependency changes.
- **Routine feature work must not modify publication behavior.** Leave coordinates,
  version derivation, credentials, repositories, workflow triggers and permissions,
  artifact composition (main, sources, and javadoc jars), and release mechanics in
  `publishing { }` and the workflows unchanged unless the task specifically requires it.
- **Descriptive publication metadata must stay accurate.** The POM `name` and
  `description` are not publication behavior. When a change alters what the library
  provides, such as adding a domain, update them in the same change so the published
  artifact describes the library's actual functionality.
- **Maven Central, if added later, is an additional publishing target.** Add a second
  repository or workflow step. Do not replace or break GitHub Packages for existing
  consumers, and do not add PGP signing for GitHub Packages alone.

## Kotlin design rules

- Booking lifecycle phases are **interfaces**, not classes, enums, or sealed data carriers.
- In the booking lifecycle, seal the taxonomy (`BookingLifecycle`, `Active`, `Terminal`)
  and never seal the phases. Keep transition functions abstract, with covariant-friendly
  return types, and keep terminal interfaces transition-free.
- Financial document stages are final classes of a sealed class, with private
  constructors, because the library owns their invariants.
- Payment records are final, non-data classes with equality over every field. Where
  creation checks other records they have private constructors with `create` and
  `restore` factories. Reconciliation results are final classes with private constructors
  and a static `reconcile`.
- Prefer compile-time topology over runtime string or enum state validation.
- Keep the public API small. Each domain should be readable in minutes. Don't add
  abstraction layers, reflection, classpath scanning, service locators, dependency
  injection, or coroutines to the `:domain` main source set.
- In `:runtime`, composition is explicit: dependencies are constructed in order in
  `commerceRuntime(...)` with ordinary Kotlin. No DI container, annotation scanning, or
  reflection-based wiring (Hoplite's reflective configuration decoding is the one
  accepted use of reflection).
- Keep Java callers in mind: `@JvmStatic` on companion factories, `@JvmField` on
  constants, `@JvmSynthetic` on internal helpers that must not be callable from Java.
- Do not use explicit `public` visibility modifiers in Kotlin when `public` is already the language default, in either module. Prefer idiomatic implicit public visibility. Use explicit visibility modifiers only when they change semantics, such as `private`, `protected`, or `internal`.
- Keep explicit API types and KDoc for published declarations. Kotlin's `explicitApi()`
  compiler mode is disabled because it requires redundant `public` modifiers. There is
  no ktlint standard rule configured specifically for redundant `public`; review that
  convention when editing public API declarations.
- KDoc describes semantics: what a phase means, whether it is active or terminal, which
  edges are legal, and that implementations are application-owned. It does not describe
  implementation trivia or business policy. Never write something like "called after a
  deposit is paid".
- Invalid arguments fail with `require` (`IllegalArgumentException`). A broken SPI
  contract, such as a history returning the wrong snapshot, fails with `check`
  (`IllegalStateException`).
- Code style: `kotlin.code.style=official`.
- The Jlleitschuh ktlint Gradle plugin (14.2.0) checks Kotlin sources and scripts during
  `check`/`build`. Run `./gradlew ktlintCheck` before committing and
  `./gradlew ktlintFormat` to format all Kotlin sources and scripts. `compileKotlin`
  formats main sources first; in `build`, the lint check runs before that formatting.
  Keep `.editorconfig` as the shared source of ktlint settings. A baseline can be
  generated with `ktlintGenerateBaseline` for existing violations, but format tasks
  ignore baselines. Do not add another overlapping formatter. The plugin is applied to
  the root project and both modules with one shared configuration; Spotless is
  deliberately not used, even though the reference backend uses it.

## Testing expectations

- Stack: Kotest 6.2.5 `FunSpec` with Kotest assertions on the JUnit Platform, plus MockK
  1.14.11. Do not use JUnit assertion APIs.
- Use concrete, test-owned fixtures in `fixtures/TestBookingModels.kt` to show lifecycle
  semantics. Mocks are for showing that the protocol is mockable. Keep at least one real
  MockK test that creates, stubs, calls, and verifies a mock. Don't rely on mocks alone.
- Changes to lifecycle semantics must come with tests showing adopter-owned concrete
  implementations, legal transition paths, cancellation paths, phase classification
  (including exhaustive `when`), covariant transition returns, terminal behavior, and
  mocking compatibility where relevant.
- The "protocol shape" tests use reflection to assert the exact set of transitions on each
  phase, that every transition is abstract, and that terminal interfaces declare nothing.
  Update them deliberately when the topology changes. Never loosen them to make a change pass.
- Do not add a compile-testing library to prove that illegal calls fail to compile.
  Illegal calls are documented in the comment at the top of `BookingLifecycleSpec.kt` and
  in the README.
- Financial tests use real `Money`, `LineItem`, and `ChangeOrder` values. Never mock
  value objects. MockK is for collaborators, chiefly `FinancialDocumentHistory`, where
  the tests verify exactly which lookups happen. Changes to financial semantics must
  come with tests covering creation at every stage, versioning, lifecycle typing,
  immutability (including defensive copies), line and document calculations, currency
  rejection, change-order success and atomic failure, and history lookups.
- `FinancialDocumentSpec` uses reflection to assert each stage's exact public operations,
  that no stage has a callable constructor, and that snapshots hold no reference to other
  snapshots. Update these deliberately. Never loosen them to make a change pass.
- Payment tests use real records, documents, and money; there is no collaborator worth
  mocking. Compare derived amounts numerically (`shouldBeNumerically` in the fixtures),
  because `Money` equality is scale-sensitive. Changes to payment semantics must come with
  tests covering creation and rejection of every record, currency checks, exact-snapshot
  and lineage allocation, reversals versus refunds, optional refund allocations, every
  reconciliation rejection, and the end-to-end history in `PaymentDomainSpec`.
- `PaymentDomainSpec` uses reflection to assert that records have no callable
  constructor where creation checks other records, hold no mutable state, embed no
  document or record, and that the financial types never mention the payment package.
  Update these deliberately. Never loosen them to make a change pass.
- Tests must run on Java 25. Never lower the test runtime to get tests passing.
- Runtime tests use Kotest `FunSpec` as well. Database specs run against a real
  PostgreSQL provided by the build (the Docker CLI build service, or
  `TEST_DATABASE_JDBC_URL`), create their own database through
  `testing/TestDatabase.kt`, and apply the real migrations through `MigrationLifecycle`.
  Never maintain a separate test schema, never use H2, and never use Testcontainers.
- Runtime tests should prove behavior that matters: configuration loading, the error
  contract, serialization of DTOs, transaction commit and rollback, repository behavior,
  the migration contract, and the composed runtime over real HTTP.
- `MigrationLifecycleSpec` and `CommerceRuntimeStartupSpec` pin the
  [Migration contract](#migration-contract) against PostgreSQL: internal runtime
  discovery, runtime-before-application ordering proven by a real dependency, independent
  version spaces, idempotent and concurrent migration, validation, and startup gating on
  runtime and application failures. Never replace them with Flyway mocks. The dependency
  tests retain a test-only stand-in runtime stream (`src/test/resources/db/testruntime`,
  selected through `RuntimeMigrations`' internal `location` parameter). It is never
  published; applications can never select a runtime location.
- The tests are the runtime's only executable consumer in this repository.
  `CommerceRuntimeSpec` composes the runtime the way a concrete application does: explicit
  `ApplicationContributions`, `commerceRuntime(...)`, `start()`, real HTTP and
  PostgreSQL, then `close()`. Keep the transaction coverage: contributed routes receive
  `CommerceRuntimeContext`, use `context.transactor`, and persist a contributed migration's
  table; several application-owned writes sharing one `Transaction` commit and roll back
  together; an application route's failure rolls back its writes.
- `OfferingsSnapshotRepositorySpec` covers the first real commerce repository against
  PostgreSQL, including price and order round trips, duplicate and predecessor rejection,
  and a single transaction spanning an application row and commerce snapshot. It proves
  both commit and rollback from a later transaction.
- Session tests run against PostgreSQL and a hand-driven `MutableClock`, never the wall
  clock. Keep the security coverage: `SecureRandom` token generation, digest-only storage
  (checked in SQL), digest uniqueness, `UserId` and `ServiceId` round trips, expired,
  revoked, unknown, and identifier-as-token rejection, `revokeAll` isolation, token-free
  logs, `401` versus `403`, and authorization that follows permission changes during a
  session. `CommerceRuntimeSpec` keeps the end-to-end flow: the test application
  authenticates identity itself, issues a session through `context.sessions`, and recovers
  the principal from the token over real HTTP.

Run:

```bash
./gradlew clean test
```

```powershell
.\gradlew.bat clean test
```

`:domain:test` needs no Docker; `:runtime:test` does.

The build cache is on. Add `--no-build-cache` to force the tests to actually run.

## Documentation synchronization

Any change to lifecycle topology or semantics must update, in the same change:

- KDoc in `BookingLifecycle.kt` or in the financial sources;
- `domain/README.md`: the relevant Mermaid diagram, transition tree, phase or stage
  table, API listing, examples, and compile-error list;
- this file: the topology blocks, invariants, and rules;
- the tests.

Changes to module boundaries, artifact coordinates, the runtime's HTTP or error
contract, configuration, or migrations must update the root `README.md` and
`runtime/README.md` in the same change.

Code and documentation must never disagree about legal lifecycle edges or Maven
coordinates. In the booking lifecycle, use the term **phase**. In the financial domain,
use **stage** (`Estimate`, `Quote`, `Invoice`) and **snapshot** (one immutable version).
In the payment domain, use **record** for an immutable fact, **allocation** for applying
money to a document, **reversal** for a correction, **refund** only for money that left
the business, and **reconciliation** for derived results.
Reserve "state" for the rejected state-property design and for the phrase "the transition
methods are the state machine".

## Scope discipline

When solving a focused issue, do not opportunistically add persistence or serialization to
`:domain`, payment logic outside the payment package, customer/CRM models or fields,
workflow engines, generic transition contexts, event buses, new domains, or new modules
unless the requested work requires them. Do not couple the booking lifecycle to the other
domains, and do not make the financial documents depend on payments. Prefer narrow
architectural evolution.

The project has exactly two modules, `:domain` and `:runtime`. Do not create speculative
modules (`runtime-core`, `runtime-http4k`, `runtime-postgres`, `runtime-jdbi`,
`runtime-testing`, `commerce-domain-persistence`, an executable `app`/`server`/`runner`
module, ...). Split
`:runtime` only when a concrete consumer demonstrates the need, for example orchestration
without http4k or PostgreSQL.

In `:runtime`, do not create empty packages or placeholder layers for capabilities that
have no code yet, and do not implement speculative endpoints or operations to fill out a
list.

## Decision heuristics

Before changing the core, ask:

- Does this describe the booking lifecycle itself, or activity around a booking?
- Is this a lifecycle phase or application data?
- Is this a legal transition edge, or a business rule controlling that edge?
- Can an adopter own this concern without weakening the shared lifecycle protocol?
- Would multiple unrelated booking systems reasonably agree on this concept?
- Can the type system express this naturally without introducing a runtime framework?

If the answers point away from the lifecycle itself, the change belongs in adopter code
or in `:runtime`, and only if it is generic across the known consumers.

## Open questions

These are intentionally unresolved. Do not settle them incidentally.

- **Booking identity (unresolved; application-owned).** The protocol does not say how an
  `InitialRequest`, its `Quote`, and its `Booked` model are known to be the same booking.
  `BookingLifecycle` is reusable phase topology and legal transitions; concrete application
  booking models own identity, data, relationships, and persistence. An application may
  preserve one application-defined booking or opportunity ID across
  `InitialRequest → Quote → Booked`, but that is not a generic commerce invariant. Do not
  add a generic `BookingId`, add IDs to the lifecycle interfaces, or reintroduce a generic
  `Booking` record without an explicit architectural decision.
- **Phase exclusivity enforcement.** One class can currently implement several phases.
  Whether to enforce exclusivity at the type level is undecided.
- **Booking and financial coupling.** The two domains are deliberately independent.
  Whether the library should ever offer a bridge between booking phases and financial
  documents is undecided. Don't add one incidentally.
- **Booking and payment coupling.** Payments reference financial documents, not bookings.
  Whether the library should link payments to booking phases is undecided.
- **Store credit.** Credit balances, gift cards, and credit memos are a possible future
  bounded context. Don't model them as refunds or allocations in the meantime.
- **Chronological validation.** Reconciliation validates records as a whole, ignoring
  timestamp order, so a history in which an over-allocation was later reversed is
  accepted. Whether to reject histories that were inconsistent at some earlier moment is
  undecided.
- **Financial document numbering and dates.** Human-facing document numbers, issue and due
  dates, and customer relationships are application data.
- **Financial document recipient snapshots (unresolved).** Should an issued financial
  document eventually carry an immutable recipient or billing snapshot as part of the
  financial fact itself? That concerns document issuance semantics and historical
  correctness, not customer ownership, and needs its own design discussion. Until then, do
  not add `InvoiceRecipient`, `FinancialDocumentRecipient`, `BillTo`, `SoldTo`,
  `BillingContact`, `RecipientSnapshot`, `CustomerSnapshot`, or name/email/address fields
  to `FinancialDocument`, and never use such a type to reintroduce a customer reference.
- **The booking extension seam.** How an application supplies strongly typed booking
  details (and phase rehydration, transition policy, serializers, and persistence) to
  `:runtime` is undecided. `runtime/README.md` lists the responsibilities identified so
  far. Do not invent the API incidentally, and never substitute opaque JSON.
- **Beyond sessions (deferred).** Service credentials (API keys) for `ServiceId`s, sliding
  expiry, and CSRF support for cookie sessions are future work. So is purging expired and
  revoked session rows: the purge itself would be a runtime capability, while when and how
  often to run it is application or deployment policy; the runtime adds no background jobs
  or schedulers. Do not settle these incidentally.
- **Capability selection (intentionally deferred).** The runtime currently serves only its
  infrastructure routes, `/health` and `/ready`, which stay enabled. Any future commerce
  routes would be served by every runtime. Whether and how an application selects commerce
  capabilities will be designed only after concrete consumers show the composition they
  need. Until then, do not add capability flags, per-route toggles, or a capability
  framework.
- **A shared `Active.cancel()`.** All active phases can be cancelled, but `cancel()` is
  declared per phase. Code holding only an `Active` must use `when` to cancel. Hoisting
  `cancel()` to `Active` would change the public API shape, so leave that for a deliberate
  decision.
