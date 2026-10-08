# commerce

[![CI](https://github.com/castab/commerce/actions/workflows/ci.yml/badge.svg)](https://github.com/castab/commerce/actions/workflows/ci.yml)

A Kotlin/JVM commerce toolkit for building service businesses: catering, mobile auto
detailing, computer repair, pet and service appointments, general point of sale, and the
next ones. It has two independently consumable modules:

```text
commerce
│
├── commerce-domain        (Gradle project :domain)
│
│   Reusable commerce vocabulary and invariants.
│   Depends on kotlin-stdlib only. Can be consumed independently.
│
└── commerce-runtime       (Gradle project :runtime)
    Opinionated runtime/application machinery used
    to construct concrete commerce applications:
    http4k on Jetty, PostgreSQL through HikariCP, JDBI, and Flyway,
    kotlinx.serialization, Hoplite configuration, explicit composition.
    Depends on commerce-domain. A library, not an application.
```

| Artifact | Coordinates | Documentation |
|---|---|---|
| commerce-domain | `io.github.castab:commerce-domain:<version>` | [domain/README.md](domain/README.md) |
| commerce-runtime | `io.github.castab:commerce-runtime:<version>` | [runtime/README.md](runtime/README.md) |

> **Requires Java 25.** Both artifacts are compiled to Java 25 bytecode, tested on Java 25,
> and require a Java 25 or newer runtime.

## Three layers, two modules

```text
commerce-domain                  (this repository)
      │
      ▼
commerce-runtime                 (this repository)
      │
      ▼
concrete commerce application    (the consuming project)
```

1. **Domain.** `commerce-domain` holds independent, reusable commerce concepts, facts,
   invariants, and protocols: self-contained financial lines,
   the booking lifecycle topology, estimates, quotes, and
   invoices (line items, money, change orders), payments, allocations, refunds,
   reconciliation, the provider-neutral payment adapter contract, and principals, roles,
   and permissions. It defines no customer, booking record, inquiry, contact, or location,
   and it knows nothing about HTTP, databases, serialization, or frameworks.
2. **Runtime.** `commerce-runtime` is the opinionated, reusable machinery from which a
   commerce application is assembled: operations, transactions, PostgreSQL persistence,
   HTTP on http4k and Jetty, errors, health, the configuration model and its loader,
   application contribution points, append-only
   financial-document snapshot persistence, payment records and allocations, refunds and refund allocations, derived
   ledger reconciliation, and
   authenticated principal sessions, persistent principal and RBAC state, live permission
   resolution, and authorization administration over HTTP. It manages a session only after
   the application has proven identity and the runtime confirms a known, active principal;
   it never sees human credentials. It does authenticate service principals itself: Argon2id-
   hashed service credentials (several per service, for rotation) exchanged for short-lived
   signed bearer tokens that carry identity only, so services are authorized through their
   current roles like users. Stored role grants must match the running permission catalog,
   the runtime's and the application's permission definitions composed at startup, which
   applications may expose with an opt-in catalog route alongside a current-principal route
   that reports resolved effective permissions.
   It owns no customer or other application data model. It is a
   library. It is not itself an application, and it provides no default application and
   no `main()`. It defines the configuration it requires but ships no `application.conf`,
   and it emits logs through the SLF4J API but selects no logging backend and ships no
   logging configuration.
3. **Concrete application.** The consuming project, for example Fiona's catering
   application or a detailing, repair, pet salon, or point-of-sale application. It
   depends on `commerce-runtime` and supplies its business-specific behavior and details
   through explicit `ApplicationContributions`. It owns its business entities (customers,
   inquiries, concrete bookings that implement lifecycle phases, contacts, locations) and
   their relationships to commerce facts, which it can persist in the runtime's shared
   transaction. It owns human credentials, their verification, login endpoints, bootstrap policy,
   and vertical staff profiles, and hands the resulting `PrincipalId` to the runtime's sessions. It owns `main()` and its process
   lifecycle, its deployment configuration (`application.conf` and environment), and its
   logging backend (an SLF4J provider such as Logback) and logging configuration, and it
   creates and starts the runtime.

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

## Application pricing to financial documents

The application owns products, catalog persistence, selection rules, availability,
price tables, discounts, overrides, and server-side pricing. It submits final `LineItem`
snapshots through authorized operations. Commerce validates financial and lifecycle
invariants without product lookups or catalog provenance.

```text
Untrusted request → trusted application pricing / authorized staff decision
                  → self-contained LineItem list → Estimate / Quote / Invoice
                  → FinancialLedger persistence and reconciliation
```

A BFF service token identifies the technical caller. It does not authorize browser amounts
or prove a staff member's authority. The application must construct public prices through
trusted server policy and securely authorize and attribute staff-defined edits. A
caller-supplied staff ID alone is insufficient. Once committed by that authorized operation,
the document is authoritative and immutable; later catalog or pricing changes cannot
rewrite it. Flat charges, quantities, supplied tax, signed credit lines, and stable-ID
price replacements use the existing financial model.

This is a breaking pre-production release: the shared catalog subsystem is removed and
developer databases must be recreated. See [the API and consumer migration](CATALOG_REMOVAL.md).

## Durable financial ledger

`commerce-runtime` persists immutable `Estimate`, `Quote`, and `Invoice` snapshots in
`commerce.financial_document_snapshots`, each row holding the snapshot's ordered lines as
JSONB. Stage transitions and change
orders append a new version; previous versions remain available. A lineage may begin at
any of the three stages. The runtime stores exact decimal line facts and derives document
totals from the domain model when restoring them. `FinancialLedger.version`,
`latestVersion`, and `versionHistory` return each persisted snapshot with its
database-assigned `createdAt`. Edits and promotions require the reviewed document version;
stale or competing mutations fail with `Conflict`.

Values that only make up a financial snapshot live in its row. Payments, allocations,
refunds, principals, roles, and sessions remain relational independent facts. The fresh
`V1__commerce_baseline.sql` establishes this schema directly. Existing developer databases
must be recreated rather than repaired. See the
[runtime persistence notes](runtime/README.md#aggregate-snapshot-persistence).

`PaymentRecord` describes money received. A separate `PaymentAllocation` connects part of
that money to an exact `(document id, version)` snapshot. An allocation stays attached to
its original version as later snapshots are issued. `FinancialLedger.reconcileLatest` uses
the domain's `FinancialDocumentReconciliation` across the whole lineage; gross allocated,
net applied, and balance are derived and never stored on the document. External payment
references are unique by provider and reference. The database rejects two successors of
the same snapshot, and allocation operations lock the payment while checking available
funds.

A `RefundRecord` describes money that left the business and belongs to a payment, not a
document. When the refunded money had been applied, the caller names the allocations it
unwinds, and a `RefundAllocation` records each unwound amount; money refunded from the
payment's unapplied value needs none. `FinancialLedger.recordRefund` validates the refund
and its refund allocations together against the payment's complete history and records
them atomically. Refunds reduce the payment's net received money, refund allocations
reduce its net allocated money and the document lineage's net applied amount, and refunded
money never becomes available to allocate again. Refunds and allocations lock the same
payment row. The runtime persists allocation reversals nowhere yet; they remain a domain
concept. It contacts no payment provider: it records the facts an application or adapter
reports, identified by the provider-neutral `ExternalRefundReference`.

The persisted facts can be read back without the mutation responses:
`FinancialLedger.paymentHistory(paymentId)` returns a `PaymentHistory` (the payment, all its
allocations, refunds, and refund allocations, and the reconciliation derived from them), and
`paymentHistoriesForLineage(documentId)` returns the histories of every payment ever
allocated to any version of a document lineage. `unappliedPayments()` discovers standalone
and partially applied payments with money still available to allocate; the amount is
`PaymentHistory.reconciliation.unallocated`. These reads use one `REPEATABLE_READ`
snapshot and take no lock.

The shared HTTP error envelope can add `violations` with application-supplied stable codes
to a `validation_failed` response, without coupling the error model to a product catalog.

Applications create documents from their own authoritative pricing and pass them to
`context.financialLedger.create(...)`. For an application-owned association, use
`context.transactor.inTransaction { transaction -> ... }` and
`context.financialLedger.create(transaction, document)` with the application's repository
write in that same transaction. The runtime provides no generic document-creation HTTP
endpoint; the application owns its route, authorization, relationships, and pricing.
See [the runtime ledger API](runtime/README.md#financial-ledger).

Deposit requirements are generic approved financial terms in `commerce.deposit`. Fixed
money or an explicit decimal percentage resolves against one exact document snapshot;
percentage resolution rounds HALF_UP to the currency's minor units. The amount is frozen
at approval, and later document versions leave it unchanged. `FinancialLedger` appends
Active replacements/reactivations and Withdrawn revisions to one immutable lineage
stream, and returns their database creation times. Satisfaction is derived from current
lineage reconciliation (`netApplied >= requiredAmount`), so a refund can undo financial
satisfaction. Applications own every workflow consequence; booking remains independent.

The baseline includes deposit requirements and maintains each lineage's current snapshot reference for safe
document/requirement write serialization. `financialLineages(ids)` reads explicit lineages
with a fixed set of queries in one `REPEATABLE_READ` snapshot: current documents,
reconciliation, requirements, satisfaction, and objective document/requirement/allocation/
refund-unwind activity times. Hosts conventionally gate reads with
`commerce.financial-document.read` and mutations with the new
`commerce.deposit-requirement.manage`. See the
[deposit API and concurrency contract](runtime/README.md#deposit-requirements-and-financial-lineage-reads).

The module dependency points one way: `:runtime` → `:domain`, never the reverse. The build
enforces it. `:domain`'s `check` fails if its runtime classpath ever contains anything
beyond `kotlin-stdlib`.

## Intended usage

```text
Catering Application           Detailing Application          POS Application
  (owns main())                  (owns main())                  (owns main())
        │                              │                              │
        ▼                              ▼                              ▼
commerce-runtime               commerce-runtime               commerce-runtime
        │                              │                              │
        ▼                              ▼                              ▼
commerce-domain                commerce-domain                commerce-domain

Payment adapter (e.g. a future stripe-adapter)
        │
        ▼
commerce-domain
```

Each concrete application composes the runtime in its own entry point:

```kotlin
// In the catering (or detailing, or POS) application's own project.
fun main() {
    val runtime =
        commerceRuntime(
            configuration = CommerceRuntimeConfiguration.load(),
            application =
                ApplicationContributions(
                    migrations =
                        ApplicationMigrations(
                            schema = "catering",
                            locations = listOf("classpath:db/migration"),
                        ),
                    routes = { context -> cateringRoutes(context) },
                ),
        )

    runtime.start()

    // The application owns its process lifecycle from here.
    Runtime.getRuntime().addShutdownHook(Thread { runtime.close() })
    Thread.currentThread().join()
}
```

- **An adapter** that only needs the shared vocabulary, such as the provider-neutral
  payment contract, depends on `commerce-domain` alone. It never picks up
  `commerce-runtime`, http4k, Jetty, JDBI, HikariCP, PostgreSQL, Flyway, or Hoplite.
- **An application** depends on `commerce-runtime`, writes its own `main`, and calls
  `commerceRuntime(configuration, application)` with its explicit contributions. It does
  not fork or copy the runtime. The runtime has no default application, so even an
  application with nothing to add passes `ApplicationContributions()` deliberately.
- **Migrations have two owners.** The runtime discovers and applies its own migrations
  (the `commerce` schema) first, then the application's migrations from its contributed
  locations, each with its own Flyway history and version space. Nothing is composed or
  served until both are current. Runtime tables that applications reference are a
  compatibility surface. See
  [runtime/README.md](runtime/README.md#database-and-migrations).
- **Booking is optional.** Booking is one commerce capability, not the root of commerce. A
  point-of-sale application uses invoices, payments, allocations, refunds, and
  reconciliation (with its own customer model, if it has one) without ever creating a
  booking.

`commerce-runtime` does **not** define `CateringBooking`, `DetailingBooking`,
`RepairBooking`, `GroomingBooking`, or any other business-specific booking model. Neither
module will. Those types belong to the applications that need them. The next design step
is a strongly typed, compile-time **booking extension seam** that lets each application
supply its own booking details while reusing the generic machinery. See
[runtime/README.md](runtime/README.md#booking-extension-direction).

## Installation

Releases are published to **GitHub Packages**:

| | |
|---|---|
| Repository | `https://maven.pkg.github.com/castab/commerce` |
| Versions | [GitHub Releases](https://github.com/castab/commerce/releases). Both artifacts share one version: a release tagged `v0.1.0` publishes `commerce-domain:0.1.0` and `commerce-runtime:0.1.0`. |

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/castab/commerce")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        }
        content {
            includeGroup("io.github.castab")
        }
    }
}

dependencies {
    // Either the domain alone (for example, in a payment adapter)...
    implementation("io.github.castab:commerce-domain:0.1.0")
    // ...or the runtime (in a concrete application), which brings the same version of
    // commerce-domain with it.
    implementation("io.github.castab:commerce-runtime:0.1.0")
}
```

GitHub Packages requires authentication even to download public packages. See
[Authentication](domain/README.md#authentication-is-required-even-for-public-packages).

To build against an unreleased checkout, include it as a composite build. Gradle matches
included projects by project name (`domain`, `runtime`), not by artifactId, so map the
coordinates explicitly:

```kotlin
// settings.gradle.kts of the consuming build
includeBuild("../commerce") {
    dependencySubstitution {
        substitute(module("io.github.castab:commerce-domain")).using(project(":domain"))
        substitute(module("io.github.castab:commerce-runtime")).using(project(":runtime"))
    }
}
```

## Repository layout

```text
commerce/
├── settings.gradle.kts       rootProject "commerce"; include("domain", "runtime")
├── build.gradle.kts          shared conventions: Java 25, Kotlin, ktlint, tests, publishing
├── gradle.properties
├── gradle/libs.versions.toml all versions, for both modules
├── domain/                   commerce-domain
│   ├── build.gradle.kts
│   ├── README.md
│   └── src/{main,test}/kotlin/io/github/castab/commerce/...
└── runtime/                  commerce-runtime (a library; no executable)
    ├── build.gradle.kts
    ├── README.md
    └── src/{main,test}/{kotlin,resources}
```

The domain lives in `io.github.castab.commerce.*` (booking lifecycle, financial, payment,
staff), and the runtime lives in `io.github.castab.commerce.runtime.*`. There is no
executable module in this repository: concrete applications live in their own projects.

## Requirements

| | Version | Notes |
|---|---|---|
| Java | **25** | Hard requirement for building, testing, and running both modules. Toolchain auto-download is disabled, so a missing JDK 25 fails the build. |
| Kotlin | 2.4.20 | |
| Gradle | 9.7.0 | Pinned through the wrapper (with checksum): the newest Gradle that Kotlin 2.4.20 declares full support for. |
| Docker | any recent | Only for `:runtime` tests, which start a throwaway PostgreSQL 18 container. Not needed to build or test `:domain`. |

Versions are declared in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Building and testing

```bash
./gradlew clean build
```

On Windows:

```powershell
.\gradlew.bat clean build
```

`build` compiles both modules, runs every test suite and the ktlint checks, verifies the
domain's dependency boundary, and assembles the main, sources, and javadoc jars of both
artifacts. It publishes nothing and needs no GitHub credentials. Local builds use the
version `0.0.0-SNAPSHOT`.

Module-specific commands:

| Command | What it does |
|---|---|
| `./gradlew :domain:build` | Builds and tests `commerce-domain` alone. No Docker, no database. |
| `./gradlew :runtime:test` | Runs the runtime specs against real PostgreSQL (see below). |
| `./gradlew ktlintCheck` | Checks Kotlin sources and Gradle Kotlin scripts of every project. |
| `./gradlew ktlintFormat` | Formats them. |
| `./gradlew :domain:dependencies --configuration runtimeClasspath` | Shows that the domain resolves `kotlin-stdlib` only. |

**Runtime tests and PostgreSQL.** The first `:runtime` test run starts a
`postgres:18-alpine` container through the plain Docker CLI, then removes it when the build
ends, even if tests fail. Each database spec creates its own database and applies the real
Flyway migrations. There is no H2, no Testcontainers, and no separate test schema. To use
an existing PostgreSQL server instead, set `TEST_DATABASE_JDBC_URL`,
`TEST_DATABASE_USERNAME`, and `TEST_DATABASE_PASSWORD` (the user must be allowed to
`CREATE DATABASE`).

**Formatting.** One formatter covers the whole repository: the
[ktlint-gradle](https://github.com/JLLeitschuh/ktlint-gradle) plugin 14.2.0 with the root
`.editorconfig` (ktlint official style, four-space indentation). `compileKotlin` formats
main sources first; in `build`, lint checks run before that formatting.

The build cache is enabled. To force tests to run again, add `--no-build-cache` (or use
`--rerun`).

The [CI workflow](.github/workflows/ci.yml) runs on Java 25 (Temurin) for every pull
request and every push to `main`. Its steps are ktlint, domain tests, runtime tests, and
then the full build.

## Releasing

A GitHub Release is the only point where versions are published, and one release
publishes both artifacts at the same version.

1. Merge the desired changes to `main` and confirm CI is green.
2. Create a GitHub Release with a new tag of the form `vMAJOR.MINOR.PATCH`, for example
   `v0.1.0`. Prerelease suffixes such as `v0.2.0-alpha.1` are also accepted.
3. Publishing the release triggers the [Publish workflow](.github/workflows/publish.yml).
   It validates the tag and runs `./gradlew clean build` on Java 25.
4. If every check passes, the workflow publishes `commerce-domain` and `commerce-runtime`
   at the version without the `v` to GitHub Packages. The published `commerce-runtime`
   POM depends on `commerce-domain` at that same version. If the tag is malformed or any
   check fails, nothing is published.

The workflow rejects tags that don't match the format: `0.0.1` (no `v`), `v0.1`,
`v01.0.0`, build metadata such as `v1.0.0+build.5`, and `SNAPSHOT` versions. No release
version is ever written into source-controlled files.

**Published versions are immutable.** Never try to overwrite a published version. If
`0.1.0` has a problem, fix it and release `0.1.1`. If a Publish run fails before uploading
anything, use **Re-run jobs** on that run.

## Contributing

The architectural rules are in [`AGENTS.md`](AGENTS.md): module boundaries, domain
invariants, runtime conventions, and build and publication rules. Read it before changing
either module.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
