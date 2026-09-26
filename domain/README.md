# commerce-domain

[![CI](https://github.com/castab/commerce/actions/workflows/ci.yml/badge.svg)](https://github.com/castab/commerce/actions/workflows/ci.yml)

A small Kotlin/JVM library of immutable commerce domain models and lifecycle APIs that
multiple applications can share.

It is the `:domain` module of the [`commerce`](../README.md) project and is published and
consumed on its own. It does not depend on
[`commerce-runtime`](../runtime/README.md), and it never brings in HTTP, persistence,
serialization, configuration, or logging libraries. A payment adapter, for example,
can depend on `io.github.castab:commerce-domain` alone without acquiring
`commerce-runtime`, http4k, Jetty, JDBI, HikariCP, Flyway, or PostgreSQL.

> **Requires Java 25.** The library is compiled to Java 25 bytecode, tested on Java 25,
> and requires a Java 25 or newer runtime. Older JVMs are not supported. See
> [Requirements](#requirements).

```text
io.github.castab:commerce-domain:<version>
```

It contains independent, reusable commerce concepts: the booking lifecycle protocol,
financial documents, payment reconciliation, a payment adapter contract, and principal
authorization:

| Domain | Package | What it provides |
|---|---|---|
| [Booking lifecycle](#booking-lifecycle) | `io.github.castab.commerce.booking.lifecycle` | A type-level protocol for the phases of a booking (`InitialRequest → Quote → Booked → Completed`, or `Cancelled`). Your application's own types implement the phases. |
| [Financial documents](#financial-documents) | `io.github.castab.commerce.financial` | Immutable, versioned commercial documents (`Estimate → Quote → Invoice`) with line items, change orders, derived totals, and persistence-agnostic history lookup. |
| [Payment reconciliation](#payment-reconciliation) | `io.github.castab.commerce.payment` | Immutable payment records, payment allocations, allocation reversals, refund records, and refund allocations, with derived payment and document reconciliation. |
| [Principal authorization](#principal-authorization) | `io.github.castab.commerce.staff` | Human and service identities, extensible roles and permissions, resolver ports, and additive role-based authorization. |
| [Payment adapter contract](#payment-adapter-contract) | `io.github.castab.commerce.payment.adapter` | Provider-neutral payment and refund instructions, observations, capability descriptions, and event decisions. |

The booking lifecycle protocol and financial documents are independent of each other and
of any customer. The payment domain references financial documents, one way only. A
financial document never knows about its payments.

The library defines no customer, booking record, inquiry, contact, or location. Those are
application entities, and so are their relationships to the facts this library models
(which customer a document concerns, which inquiry produced an estimate, which booking an
invoice belongs to). See [Application-owned entities and relationships](#application-owned-entities-and-relationships).

The domains share one design stance. Lifecycle progression is expressed by the type
system rather than a mutable status field: an operation that is not legal in a stage or
phase does not exist on that type. Facts are immutable, and anything derivable (totals,
balances) is derived rather than stored. The library stays free of persistence,
frameworks, serialization, and payment-processor integrations. Its only runtime dependency
is `kotlin-stdlib`.

## Contents

- [Installation](#installation)
- [Booking lifecycle](#booking-lifecycle)
- [Application-owned entities and relationships](#application-owned-entities-and-relationships)
- [Financial documents](#financial-documents)
- [Payment reconciliation](#payment-reconciliation)
- [Principal authorization](#principal-authorization)
- [Payment adapter contract](#payment-adapter-contract)
- [Using both domains together](#using-both-domains-together)
- [What this library is not](#what-this-library-is-not)
- [Requirements](#requirements)
- [Building and testing](#building-and-testing)
- [Releasing](#releasing)
- [Current scope](#current-scope)
- [Relationship to commerce-runtime](#relationship-to-commerce-runtime)
- [License](#license)

## Installation

Releases are published to **GitHub Packages** as a Maven artifact:

| | |
|---|---|
| Coordinates | `io.github.castab:commerce-domain:<version>` |
| Repository | `https://maven.pkg.github.com/castab/commerce` (the GitHub repository that publishes the package) |
| Versions | [GitHub Releases](https://github.com/castab/commerce/releases). A release tagged `v0.0.1` is published as version `0.0.1`. `commerce-domain` and `commerce-runtime` are always released together at the same version. |

A consuming build needs **both** the repository declaration and the dependency.
`mavenCentral()` alone is not enough.

Gradle (Kotlin DSL):

```kotlin
// build.gradle.kts (or dependencyResolutionManagement in settings.gradle.kts)
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/castab/commerce")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        }
        // Only ask GitHub Packages for this library's group.
        content {
            includeGroup("io.github.castab")
        }
    }
}

dependencies {
    implementation("io.github.castab:commerce-domain:0.0.1")
}
```

Maven:

```xml
<!-- pom.xml -->
<repositories>
  <repository>
    <id>github-castab</id>
    <url>https://maven.pkg.github.com/castab/commerce</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>io.github.castab</groupId>
    <artifactId>commerce-domain</artifactId>
    <version>0.0.1</version>
  </dependency>
</dependencies>
```

For Maven, put the credentials in a `<server>` with the same `<id>` (`github-castab`) in
your user-level `~/.m2/settings.xml`, never in the project's `pom.xml`.

The consuming project must also build and run on Java 25 (for example,
`kotlin { jvmToolchain(25) }` or `<maven.compiler.release>25</maven.compiler.release>`).

### Authentication is required, even for public packages

GitHub Packages' Maven registry requires authentication to **download** packages,
including public ones.

- **On your machine:** create a GitHub personal access token (classic) with the
  `read:packages` scope, and put it in your **user-level** Gradle properties file,
  `~/.gradle/gradle.properties`:

  ```properties
  gpr.user=<your-github-username>
  gpr.key=<your-personal-access-token>
  ```

  These credentials belong to you, not to any project. Never put tokens in a
  repository's `build.gradle.kts`, `settings.gradle.kts`, or `gradle.properties`, and
  never commit them.

- **In GitHub Actions:** provide `GITHUB_ACTOR` and `GITHUB_TOKEN` to the Gradle step
  (for example, `env: GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}`) and give the job
  `packages: read` permission. A workflow's `GITHUB_TOKEN` can read the package only if
  the consuming repository has been granted read access to it in the package's access
  settings. Otherwise, store a classic personal access token with `read:packages` as an
  Actions secret and use that instead.

### Local development against a checkout

To build against an unreleased local checkout instead, use a Gradle
[composite build](https://docs.gradle.org/current/userguide/composite_builds.html). Gradle
substitutes the checkout for the dependency, so no registry or credentials are involved:

```kotlin
// settings.gradle.kts of the consuming build
includeBuild("../commerce") { // path to your checkout of the commerce repository
    // Gradle matches included projects by project name (`domain`), not by the published
    // artifactId, so map the coordinates explicitly.
    dependencySubstitution {
        substitute(module("io.github.castab:commerce-domain")).using(project(":domain"))
    }
}
```

```kotlin
// build.gradle.kts of the consuming build
dependencies {
    implementation("io.github.castab:commerce-domain:0.0.0-SNAPSHOT")
}
```

## Booking lifecycle

Package `io.github.castab.commerce.booking.lifecycle`. A type-level lifecycle for expressing
a booking directly through application-owned domain types.

The booking lifecycle does not own your booking data. It gives your booking-domain types a
common lifecycle vocabulary and a compile-time topology of legal transitions.

> The library owns the booking lifecycle type hierarchy and its legal transition topology.
> Consuming applications own the concrete business data and the conditions necessary to
> perform those transitions.

Put shortly: **the library defines what may legally follow a lifecycle phase. Your
application defines whether, when, and how that transition occurs.**

### Why this exists

Many applications agree that a booking moves through the same broad phases:

```text
InitialRequest → Quote → Booked → Completed
```

They disagree completely about what the objects in those phases contain. A caterer's
booking might carry:

```text
customer, menu selections, guest count, deposit, invoice
```

An equipment rental's booking might carry:

```text
renter, equipment list, rental dates, security hold
```

The two apps share the lifecycle topology: which phases exist and which may follow which.
They don't share business data. The booking lifecycle captures the shared part and leaves the rest
to each application.

### The core idea

**Application models implement lifecycle phase interfaces directly.**

```kotlin
// CustomerId is the application's own type; this library defines no customer concept.
data class CateringQuote(
    val customerId: CustomerId,
    val total: BigDecimal,
) : BookingLifecycle.Active.Quote {
    // transition implementations, shown below
}
```

`CateringQuote` does not *contain* the `Quote` phase. It *is* your application's
concrete representation of the `Quote` phase.

This library is deliberately **not** centered on a state property such as:

```kotlin
data class Booking(
    val state: BookingState, // not the model this library is built around
)
```

A lifecycle does not advance by mutating a `state` field. It advances when one
lifecycle-typed application model is transformed into the next:
`CateringQuote.toBooking()` returns a `CateringBooking`.

### Lifecycle

```mermaid
stateDiagram-v2
    [*] --> InitialRequest
    [*] --> Quote

    InitialRequest --> Quote
    InitialRequest --> Cancelled

    Quote --> Booked
    Quote --> Cancelled

    Booked --> Completed
    Booked --> Cancelled

    Cancelled --> [*]
    Completed --> [*]
```

The transition functions on each phase:

```text
InitialRequest
 ├── toQuote() ───→ Quote
 └── cancel() ────→ Cancelled

Quote
 ├── toBooking() ─→ Booked
 └── cancel() ────→ Cancelled

Booked
 ├── complete() ──→ Completed
 └── cancel() ────→ Cancelled

Completed
 └── no lifecycle transitions

Cancelled
 └── no lifecycle transitions
```

#### Two entry points

A lifecycle can begin at either of two phases:

- **`InitialRequest`**: the generic pre-quote phase. Your application decides what starts
  it, for example an inquiry submitted through its own channels.
- **`Quote`**: the opportunity started outside your application (a phone call, a text,
  an email, an in-person conversation, another system), and the first thing your
  application records is an issued quote.

There is no library-owned factory or initial value. A lifecycle begins when you
construct a model that implements one of these two phases.

#### Phases

| Phase | Classification | Meaning | Legal transitions |
|---|---|---|---|
| `InitialRequest` | Active | A request for a booking that has not yet become an official quote. | `toQuote()`, `cancel()` |
| `Quote` | Active | A booking opportunity for which an official quote has been issued. It can also be the entry point. | `toBooking()`, `cancel()` |
| `Booked` | Active | A confirmed booking. The library does not define what "confirmed" requires. | `complete()`, `cancel()` |
| `Cancelled` | Terminal | The lifecycle ended without fulfillment. | none |
| `Completed` | Terminal | The booked service or event was fulfilled. | none |

The application owns what each phase contains. An `InitialRequest` might hold customer
details, selections, notes, or a requested date, but the lifecycle requires none of these.
`InitialRequest` is a booking phase, not a financial `Estimate` document: an application may
issue an estimate while a booking is in this phase, but relating the two is application
policy.

### Lifecycle API

This is the entire booking lifecycle API. It lives in
[`BookingLifecycle.kt`](src/main/kotlin/io/github/castab/commerce/booking/lifecycle/BookingLifecycle.kt),
shown here without KDoc:

```kotlin
package io.github.castab.commerce.booking.lifecycle

public sealed interface BookingLifecycle {

    public sealed interface Active : BookingLifecycle {

        public interface InitialRequest : Active {
            public fun toQuote(): Quote
            public fun cancel(): Terminal.Cancelled
        }

        public interface Quote : Active {
            public fun toBooking(): Booked
            public fun cancel(): Terminal.Cancelled
        }

        public interface Booked : Active {
            public fun complete(): Terminal.Completed
            public fun cancel(): Terminal.Cancelled
        }
    }

    public sealed interface Terminal : BookingLifecycle {

        public interface Cancelled : Terminal

        public interface Completed : Terminal
    }
}
```

#### Why some interfaces are sealed and others open

```text
BookingLifecycle          sealed   (the library controls the taxonomy)
├── Active                sealed
│   ├── InitialRequest    open     (your types implement these)
│   ├── Quote             open
│   └── Booked            open
└── Terminal              sealed
    ├── Cancelled         open
    └── Completed         open
```

- **Sealed classifications.** `BookingLifecycle`, `Active`, and `Terminal` are sealed,
  so the set of phases is fixed by the library. Another module cannot add a sixth phase.
  The compiler rejects it with "Extending sealed classes or interfaces from a different
  module is prohibited". This also makes `when` over a `BookingLifecycle` exhaustive
  without an `else` branch.
- **Open phases.** The five phase interfaces are ordinary interfaces. Kotlin only
  restricts the *direct* subtypes of a sealed type to the declaring module, so your
  classes in any module can implement a phase:

```text
BookingLifecycle.Active.Quote              BookingLifecycle.Active.Quote
            ▲                                          ▲
            │                                          │
      CateringQuote                              EquipmentQuote
   (catering application)                    (rental application)
```

The library controls the lifecycle vocabulary. Your application controls the concrete
representation.

Code that only knows the protocol can still handle any adopter's models exhaustively:

```kotlin
fun describe(phase: BookingLifecycle): String =
    when (phase) {
        is BookingLifecycle.Active.InitialRequest -> "Inquiry received"
        is BookingLifecycle.Active.Quote -> "Quote issued"
        is BookingLifecycle.Active.Booked -> "Booked"
        is BookingLifecycle.Terminal.Completed -> "Completed"
        is BookingLifecycle.Terminal.Cancelled -> "Cancelled"
    }
```

### Implementing the lifecycle

Below is a complete application-owned chain for a catering business. It lives in the
application's own package. Every field and every type other than `BookingLifecycle`
belongs to the application, including the identities: the library defines no booking
record, booking ID, customer, or customer ID. This application declares its own
`BookingId` and `CustomerId` and chooses to carry one `BookingId` through every phase.
That is the example's choice, not a protocol requirement: how a request, its quote, and
its booking are known to be the same booking is application-owned and remains an open
question for the library.

```kotlin
package com.example.catering

import io.github.castab.commerce.booking.lifecycle.BookingLifecycle
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

// Application-owned identities. commerce-domain defines neither.
data class BookingId(val value: UUID)
data class CustomerId(val value: UUID)

data class MenuSelection(val item: String, val servings: Int)

data class CateringInitialRequest(
    val bookingId: BookingId,
    val customerId: CustomerId,
    val eventDate: LocalDate,
    val selections: List<MenuSelection>,
    val estimatedTotal: BigDecimal,
) : BookingLifecycle.Active.InitialRequest {

    override fun toQuote(): CateringQuote =
        CateringQuote(bookingId, customerId, eventDate, selections, total = estimatedTotal)

    override fun cancel(): CancelledCateringInquiry =
        CancelledCateringInquiry(bookingId, customerId)
}

data class CateringQuote(
    val bookingId: BookingId,
    val customerId: CustomerId,
    val eventDate: LocalDate,
    val selections: List<MenuSelection>,
    val total: BigDecimal,
    val revision: Int = 1,
) : BookingLifecycle.Active.Quote {

    // A revision is application data. The result still inhabits the Quote phase.
    fun revise(newTotal: BigDecimal): CateringQuote =
        copy(total = newTotal, revision = revision + 1)

    override fun toBooking(): CateringBooking =
        CateringBooking(bookingId, customerId, eventDate, selections, invoiceTotal = total)

    override fun cancel(): DeclinedCateringQuote =
        DeclinedCateringQuote(bookingId, customerId, quotedTotal = total)
}

data class CateringBooking(
    val bookingId: BookingId,
    val customerId: CustomerId,
    val eventDate: LocalDate,
    val selections: List<MenuSelection>,
    val invoiceTotal: BigDecimal,
    val invoiceVersion: Int = 1,
) : BookingLifecycle.Active.Booked {

    // A change order produces a new invoice version. The result still inhabits Booked.
    fun applyChangeOrder(added: MenuSelection, cost: BigDecimal): CateringBooking =
        copy(
            selections = selections + added,
            invoiceTotal = invoiceTotal + cost,
            invoiceVersion = invoiceVersion + 1,
        )

    override fun complete(): CompletedCateringBooking =
        CompletedCateringBooking(bookingId, customerId, eventDate, finalTotal = invoiceTotal)

    override fun cancel(): CancelledCateringBooking =
        CancelledCateringBooking(bookingId, customerId, eventDate)
}

data class CompletedCateringBooking(
    val bookingId: BookingId,
    val customerId: CustomerId,
    val eventDate: LocalDate,
    val finalTotal: BigDecimal,
) : BookingLifecycle.Terminal.Completed

data class CancelledCateringInquiry(
    val bookingId: BookingId,
    val customerId: CustomerId,
) : BookingLifecycle.Terminal.Cancelled

data class DeclinedCateringQuote(
    val bookingId: BookingId,
    val customerId: CustomerId,
    val quotedTotal: BigDecimal,
) : BookingLifecycle.Terminal.Cancelled

data class CancelledCateringBooking(
    val bookingId: BookingId,
    val customerId: CustomerId,
    val eventDate: LocalDate,
) : BookingLifecycle.Terminal.Cancelled
```

Walking the canonical path:

```kotlin
val request = CateringInitialRequest(
    bookingId = BookingId(UUID.randomUUID()),
    customerId = CustomerId(UUID.randomUUID()),
    eventDate = LocalDate.of(2026, 11, 14),
    selections = listOf(MenuSelection("Tamales", servings = 80)),
    estimatedTotal = BigDecimal("1200.00"),
)

val quote: CateringQuote = request.toQuote()
val booking: CateringBooking = quote.toBooking()
val completed: CompletedCateringBooking = booking.complete()

// or, as one expression
request.toQuote().toBooking().complete()
```

The application also picks a concrete cancellation type for each point where the
lifecycle can end: `CancelledCateringInquiry`, `DeclinedCateringQuote`, and
`CancelledCateringBooking`. All three implement `BookingLifecycle.Terminal.Cancelled`. The
protocol cares about the outcome. The application keeps control of the data that
records it.

### Legal paths are expressed by capabilities

The transition methods are the state machine. The library has no separate transition
table, runtime validator, or `transition(from, to)` function. Each phase exposes only the
transitions that may legally follow it.

`Quote.toBooking()` expresses that `Quote → Booked` is a valid lifecycle edge. An edge
that is not legal has no method, so calling it does not compile:

```kotlin
initialRequest.complete()   // error: Unresolved reference 'complete'   (InitialRequest cannot complete)
initialRequest.toBooking()  // error: Unresolved reference 'toBooking'  (it must be quoted first)
quote.complete()            // error: Unresolved reference 'complete'   (a quote must be booked first)
completed.cancel()          // error: Unresolved reference 'cancel'     (terminal phases have no transitions)
completed.toBooking()       // error: Unresolved reference 'toBooking'
cancelled.toQuote()         // error: Unresolved reference 'toQuote'
```

Illegal paths are not rejected at runtime, because they cannot be written in the first
place. `Completed` and `Cancelled` declare no members at all, so there is no
lifecycle-native way to leave a terminal phase.

This design makes the right path easy. It does not sandbox your code. Nothing stops an
application from constructing a `CompletedCateringBooking` directly, and nothing in
the type system stops a single class from implementing two phases. Phases are meant to be
mutually exclusive, so give each concrete type exactly one phase interface. The library
makes the canonical lifecycle natural and leaves illegal edges out of the lifecycle API.
It does not police all application code.

### The library defines legality, not business policy

> Transition methods represent legal lifecycle edges, not the business prerequisites
> necessary to traverse those edges.

`fun toBooking(): Booked` means that `Quote → Booked` is a legal lifecycle transition. It
does **not** mean the library has checked that the booking's business requirements are
met. The booking lifecycle has no concept of deposits, payments, contracts, acceptance,
approval, availability, identity, timestamps, or actors.

Each application decides for itself. For example, a caterer that requires an accepted quote
and a 25% deposit before confirming might write:

```kotlin
data class CateringQuote(
    // ...fields as above, plus:
    val acceptedAt: Instant?,
    val depositReceived: BigDecimal,
) : BookingLifecycle.Active.Quote {

    override fun toBooking(): CateringBooking {
        checkNotNull(acceptedAt) { "Quote for booking $bookingId has not been accepted" }
        require(depositReceived >= total * DEPOSIT_RATE) { "Deposit for booking $bookingId is below 25%" }
        return CateringBooking(bookingId, customerId, eventDate, selections, invoiceTotal = total)
    }

    // cancel() as before

    private companion object {
        val DEPOSIT_RATE = BigDecimal("0.25")
    }
}
```

An equipment rental business may confirm unconditionally:

```kotlin
data class EquipmentQuote(
    val renterId: UUID,
    val equipment: List<String>,
    val rentalDates: ClosedRange<LocalDate>,
    val securityHold: BigDecimal,
) : BookingLifecycle.Active.Quote {

    override fun toBooking(): EquipmentRental =
        EquipmentRental(renterId, equipment, rentalDates, securityHold)

    override fun cancel(): ExpiredEquipmentQuote =
        ExpiredEquipmentQuote(renterId)
}
```

A third might require a signed contract, and a fourth, staff approval. These are all
valid adopters. The checks above are examples of *one* application's policy, not
requirements of the protocol. How a failed check is reported (exception, `Result`,
sealed outcome, or a check performed before `toBooking()` is ever called) is also the
application's choice.

### Covariant concrete return types

The protocol declares the most general return type:

```kotlin
public fun toBooking(): BookingLifecycle.Active.Booked
```

Kotlin allows an override to return a subtype, so an adopter can declare its own type:

```kotlin
override fun toBooking(): CateringBooking   // CateringBooking : BookingLifecycle.Active.Booked
```

Callers holding the concrete type keep concrete types through the whole lifecycle, with
no casts:

```kotlin
val booking: CateringBooking = quote.toBooking()
val completed: CompletedCateringBooking = booking.complete()
```

Callers holding only the protocol type get the protocol type:

```kotlin
val someQuote: BookingLifecycle.Active.Quote = quote
val someBooking: BookingLifecycle.Active.Booked = someQuote.toBooking()
```

### Application-owned data

The booking lifecycle does not own, define, or constrain any of the following:

- customers: customer identity, fields, or management
- staff identity or actors
- booking identity, and how it is carried through application-owned phase models
- selections, line items, or estimates
- quote contents and quote versions
- invoice details and invoice versions
- payments and deposits
- cancellation reasons
- refunds and complaints
- timestamps
- persistence structure

Every example type above (`BookingId`, `CustomerId`, `MenuSelection`, `CateringQuote`,
`invoiceVersion`, ...) is application code. The booking lifecycle compiles without knowing what any of it means.

An application that wants versioned, immutable quote and invoice documents can hold
[financial documents](#financial-documents) inside its phase models. See
[Using both domains together](#using-both-domains-together). Payments and refunds against
those documents can be recorded with the [payment domain](#payment-reconciliation). The
booking lifecycle itself stays independent of both.

### Quote and invoice revisions

Revisions change data *within* a phase. They do not move a booking to another phase.

```text
Quote v1  →  Quote v2  →  Quote v3          (still Quote)

Invoice v1 → change order → Invoice v2 → change order → Invoice v3   (still Booked)
```

In the catering example:

```kotlin
val revised: CateringQuote = quote
    .revise(BigDecimal("1150.00"))
    .revise(BigDecimal("1100.00"))          // revision == 3, still a BookingLifecycle.Active.Quote

val changed: CateringBooking = revised.toBooking()
    .applyChangeOrder(MenuSelection("Churros", servings = 80), BigDecimal("160.00"))
    .applyChangeOrder(MenuSelection("Horchata", servings = 80), BigDecimal("90.00"))
                                            // invoiceVersion == 3, still a BookingLifecycle.Active.Booked
```

Quote revisions stay application-owned data while the model continues to inhabit the
`Quote` phase. The same holds for invoice revisions in the `Booked` phase. That is why the
booking lifecycle has no `QuoteRevised`, `InvoiceSent`, or `InvoiceRevised` phase.

### Terminal outcomes

> Terminal booking phases are immutable historical outcomes of the booking lifecycle.

Once a booking reaches `Completed` or `Cancelled`, its lifecycle outcome does not change.
`Completed` means the booked service was fulfilled. `Cancelled` means the lifecycle ended
without fulfillment.

Being terminal with respect to the booking lifecycle **does not** mean all business
activity around the booking has ended:

```text
InitialRequest → Quote → Booked → Completed
                                     ⋮
                         customer complaint → refund
```

The lifecycle is still `Completed`, because the service was fulfilled. Likewise,
`Booked → Cancelled` may be followed by a refund, and the lifecycle is still `Cancelled`,
because fulfillment never happened.

Model such activity as separate processes that refer to the booking, outside the booking
lifecycle. Money actually returned can be recorded with the
[payment domain](#payment-reconciliation), which references financial documents rather
than bookings; the application links the two. A minimal application-owned sketch:

```kotlin
data class Refund(val bookingId: BookingId, val amount: BigDecimal, val reason: String)

val refund = Refund(completed.bookingId, BigDecimal("150.00"), reason = "late delivery")
// `completed` is still a BookingLifecycle.Terminal.Completed
```

Those processes can have lifecycles of their own. The combinations below describe
different historical situations, even though money is returned in both:

| Booking lifecycle | Payment process (outside the booking lifecycle) | What happened |
|---|---|---|
| `Completed` | refunded | The service was delivered, then money was returned. |
| `Cancelled` | refunded | The service never happened, and money was returned. |

Payments, refunds, complaints, disputes, chargebacks, invoicing, settlement, customer
support, and accounting are orthogonal to the booking lifecycle. For that reason there
are no phases such as `CompletedRefunded`, `PartiallyRefunded`, `DepositPaid`,
`ChargebackReceived`, or `ComplaintOpened`.

## Application-owned entities and relationships

This library models independent commerce facts and the invariants they protect. It does
not decide what a customer is, what fields a customer has, what a booking contains, which
customer owns a booking or a financial document, which inquiry produced an estimate, which
booking produced a quote or invoice, which contacts a booking needs, or where a booking
takes place. Those are the concrete application's entities and relationships.

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

The diagram is conceptual, not a required persistence design. Each application chooses its
own types, tables, and relationships. A catering application might define a `Customer`
with a name and email and a `CateringBooking` with a guest count and event date that
implements a lifecycle phase; a mobile-detailing application might define entirely
different types. Neither has to share a customer or booking schema through this library.

An application relates its entities to commerce facts from the outside, typically by
storing a document's `id` or `reference` next to its own records. `commerce-runtime`
provides the shared transaction boundary for writing such relationships alongside the
application's own records. Once the runtime persists commerce facts, an application will
be able to write its inquiry, the estimate, and the relationship between them in one
transaction, without either generic module knowing about the relationship.

The library deliberately offers no generic owner, party, subject, application-relationship
reference, context, or metadata field on its types to hold such relationships. (This does
not concern the library's own document references, `FinancialDocumentReference` and
`previousReference`, which identify financial-document snapshots.) Business-specific data stays
strongly typed in the application.

## Financial documents

Package `io.github.castab.commerce.financial`. Immutable, versioned commercial documents:
estimates, quotes, and invoices.

A financial document is an independent financial fact: its identity, version lineage, stage,
line items, currency, and derived totals. It carries no customer, inquiry, or booking
reference; applications persist those relationships externally. It also carries no
recipient or billing snapshot (name, email, address): whether an issued document should
ever hold an immutable recipient snapshot as part of the financial fact is an open design
question, separate from customer ownership.

> **Upgrading from 0.0.3 or 0.0.4.** Those releases required a `customerId` on every
> document, including in `create` and `restore`. It has been removed, which is a source and
> binary API change: drop the argument, and keep any customer relationship in your own
> storage.

Unlike the booking lifecycle, these are concrete library-owned types. The library enforces
the invariants that every application needs from a financial document: one identity per
lineage, gap-free versions, legal stage transitions, a single currency, and totals that
always agree with the line items. The application supplies the line items and decides when
each operation happens.

### Lifecycle

```mermaid
stateDiagram-v2
    [*] --> Estimate : Estimate.create
    [*] --> Quote : Quote.create
    [*] --> Invoice : Invoice.create

    Estimate --> Estimate : changeOrder
    Estimate --> Quote : toQuote

    Quote --> Quote : changeOrder
    Quote --> Invoice : toInvoice

    Invoice --> Invoice : changeOrder
```

```text
Estimate
 ├── changeOrder() → Estimate
 └── toQuote() ────→ Quote

Quote
 ├── changeOrder() → Quote
 └── toInvoice() ──→ Invoice

Invoice
 └── changeOrder() → Invoice   (no further lifecycle transition)
```

A lineage can **start at any stage**:

| Entry point | Typical use |
|---|---|
| `FinancialDocument.Estimate.create(...)` | You give preliminary estimates before quoting. |
| `FinancialDocument.Quote.create(...)` | You don't use estimates and issue quotes directly. |
| `FinancialDocument.Invoice.create(...)` | You invoice immediately, for example at a point of sale. |

Once a lineage exists it only moves forward, one stage at a time. There is no
`Estimate.toInvoice()` shortcut and no reverse transition (`Quote.toEstimate()`,
`Invoice.toQuote()`, `Invoice.toEstimate()`). Those calls don't compile. Starting a
lineage as an invoice is a different thing from converting an estimate into one: the
first is a supported entry point, and the second is deliberately impossible.

| Stage | Meaning |
|---|---|
| `Estimate` | A preliminary, non-binding statement of expected charges. |
| `Quote` | A formal offer to provide the listed items at the stated amounts. |
| `Invoice` | A request for payment of the listed items at the stated amounts. It describes what is owed, not whether it has been paid. |

The stage *is* the type. `FinancialDocument` is a sealed class with exactly three
subclasses, so a `when` over a document is exhaustive, and there is no `type` or `stage`
property to change.

### Snapshots, lineages, and versions

A `FinancialDocument` is one immutable snapshot. Nothing modifies it. Every change order
and every transition returns a **new** snapshot:

- with the same `id` (a `java.util.UUID` that identifies the whole lineage),
- at `version.next()`,
- with `previousVersion` equal to the source's `version`.

```text
ABC v1 Estimate
    ↓ changeOrder
ABC v2 Estimate
    ↓ toQuote
ABC v3 Quote
    ↓ changeOrder
ABC v4 Quote
    ↓ toInvoice
ABC v5 Invoice
    ↓ changeOrder
ABC v6 Invoice
```

Every snapshot exposes:

```kotlin
val id: UUID
val version: Version                          // starts at Version.INITIAL (1)
val previousVersion: Version?                 // null only for version 1
val lineItems: List<LineItem>
val currency: Currency
val subtotal: Money                           // derived
val taxAmount: Money                          // derived
val total: Money                              // derived
val reference: FinancialDocumentReference             // (id, version)
val previousReference: FinancialDocumentReference?    // (id, previousVersion)
```

`Version` is a small value type. `Version.INITIAL` is 1, `version.next()` is the next
version, and `Version.of(37)` reconstructs a stored version. `Version.of(0)` and
`Version.of(-1)` are rejected.

A `FinancialDocumentReference(id, version)` identifies exactly one snapshot. It never
contains the snapshot. Snapshots don't embed their predecessors, so loading version 1,000
of a document loads one snapshot, not a chain of 999 earlier ones.

The document types have private constructors and no `copy()`. Callers cannot invent a
version, a previous-version link, or a stage transition. The only ways to obtain a
snapshot are the `create` entry points, the lifecycle operations, and `restore` for
persistence adapters (see [History and persistence](#history-and-persistence)).

### Line items and money

```kotlin
data class LineItem(
    val id: UUID,
    val description: String,
    val subDescription: String? = null,
    val quantity: BigDecimal?,
    val price: Money,
    val taxAmount: Money,
) {
    val currency: Currency
    val subtotal: Money   // price × quantity, or price when quantity is null
    val total: Money      // subtotal + taxAmount
}

data class Money(val amount: BigDecimal, val currency: Currency)
```

- **Quantity.** When `quantity` is present, `price` is a unit price: 4 × $25 = $100.
  When `quantity` is `null`, the line is flat-priced (a service fee, labor, an
  intangible), and its subtotal is `price`.
- **Tax.** `price` never includes tax. `taxAmount` is the final tax attributable to the
  line, calculated by your application. It is not a rate and not a taxable amount.
  `total = subtotal + taxAmount`.
- **Currency.** `Money` is a `BigDecimal` plus a `java.util.Currency`. Arithmetic is exact,
  with no rounding and no conversion. Adding or subtracting USD and EUR throws. A line
  item's `price` and `taxAmount` must share a currency, and all line items in a document
  must share one.
- **Totals are derived.** A document's `subtotal`, `taxAmount`, and `total` are always
  calculated from its line items. No API accepts them, so a document can't claim a total
  that its lines don't add up to.
- **Line item ids** are UUIDs chosen by your application, and must be unique within a
  document. A document needs at least one line item.

Money equality follows `BigDecimal.equals`, so it is scale-sensitive: `10.0 USD` and
`10.00 USD` are not `==`. Compare `amount`s with `compareTo` when you mean numeric equality.

### Change orders

A modification is an explicit, immutable `ChangeOrder` of whole-line-item changes:

```kotlin
ChangeOrder(
    changes = listOf(
        ChangeOrder.Change.AddLineItem(lineItem),                     // id must be new
        ChangeOrder.Change.ReplaceLineItem(lineItemId, replacement),  // id must exist; replacement keeps it
        ChangeOrder.Change.RemoveLineItem(lineItemId),                // id must exist
    ),
)
```

- Changes apply **in order**. A later change sees the result of the earlier ones.
- Application is **atomic**. If any change fails, or the result would be invalid (no line
  items, mixed currencies), `changeOrder` throws `IllegalArgumentException` and there is no
  successor. There is never a partially applied document.
- A replacement must keep the id of the line item it replaces. Revise a line with
  `existing.copy(...)`. There is no field-level patching, so there is never a question of
  whether `null` means "unchanged" or "clear".
- A change order never changes the stage. `Estimate.changeOrder` returns an `Estimate`,
  `Quote.changeOrder` a `Quote`, and `Invoice.changeOrder` an `Invoice`.

### Examples

#### 1–5. Estimate, revise, quote, revise, invoice

```kotlin
import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

val usd = Currency.getInstance("USD")
fun usd(amount: String) = Money(BigDecimal(amount), usd)

val tamales = LineItem(
    id = UUID.randomUUID(),
    description = "Tamales",
    subDescription = "Pork and chicken, by the dozen",
    quantity = BigDecimal("20"),
    price = usd("30.00"),
    taxAmount = usd("48.00"),
)
val serviceFee = LineItem(
    id = UUID.randomUUID(),
    description = "Event service fee",
    quantity = null,                 // flat-priced
    price = usd("250.00"),
    taxAmount = usd("0.00"),
)

// 1. Create an estimate: v1, no previous version.
val estimateV1 = FinancialDocument.Estimate.create(
    id = UUID.randomUUID(),
    lineItems = listOf(tamales, serviceFee),
)
estimateV1.total                      // 898.00 USD  (600.00 + 250.00 + 48.00 tax)

// 2. Revise it with a change order: v2, still an Estimate.
val estimateV2 = estimateV1.changeOrder(
    ChangeOrder(
        changes = listOf(
            ChangeOrder.Change.ReplaceLineItem(
                lineItemId = tamales.id,
                replacement = tamales.copy(quantity = BigDecimal("25"), taxAmount = usd("60.00")),
            ),
        ),
    ),
)

// 3. Convert it to a quote: v3, same id and line items.
val quoteV3: FinancialDocument.Quote = estimateV2.toQuote()

// 4. Revise the quote: v4, still a Quote.
val quoteV4 = quoteV3.changeOrder(
    ChangeOrder(
        changes = listOf(
            ChangeOrder.Change.AddLineItem(
                LineItem(
                    id = UUID.randomUUID(),
                    description = "Horchata",
                    quantity = BigDecimal("3"),
                    price = usd("45.00"),
                    taxAmount = usd("10.80"),
                ),
            ),
        ),
    ),
)

// 5. Convert it to an invoice: v5, previousVersion = v4.
val invoiceV5: FinancialDocument.Invoice = quoteV4.toInvoice()

check(invoiceV5.id == estimateV1.id)
check(invoiceV5.previousVersion == quoteV4.version)
// estimateV1, estimateV2, quoteV3, and quoteV4 are all unchanged.
```

#### 6. Point-of-sale invoice

A sale that never had an estimate or quote starts its lineage as an invoice:

```kotlin
val receipt = FinancialDocument.Invoice.create(
    id = UUID.randomUUID(),
    lineItems = listOf(
        LineItem(
            id = UUID.randomUUID(),
            description = "Coffee beans, 1 lb",
            quantity = BigDecimal("2"),
            price = usd("14.00"),
            taxAmount = usd("2.24"),
        ),
    ),
)
receipt.version                       // v1
receipt.total                         // 30.24 USD
```

The invoice can later be corrected with `receipt.changeOrder(...)`, which produces
version 2 of the same invoice.

### History and persistence

The library never loads history on its own. A snapshot knows its predecessor's
*reference*, and your application supplies the lookup through the
`FinancialDocumentHistory` interface:

```kotlin
interface FinancialDocumentHistory {
    fun retrieveVersion(reference: FinancialDocumentReference): FinancialDocument?
    fun retrieveLatestVersion(id: UUID): FinancialDocument?
}
```

Conceptually, a persisted lineage is **one immutable snapshot per row or document**:

```text
document_id | version | previous_version | stage    | line_items | ...
ABC         | 1       | null             | ESTIMATE | [...]      |
ABC         | 2       | 1                | ESTIMATE | [...]      |
ABC         | 3       | 2                | QUOTE    | [...]      |
```

Historical snapshots are *referenced* by `(document_id, version)`, never embedded
recursively. The library prescribes no tables, collections, entities, or database.
Implement the interface over PostgreSQL, MongoDB, DynamoDB, Redis, an HTTP API, or an
in-memory map. The library doesn't know which. Totals can be stored as denormalized
columns if you like, but they are recalculated from the line items when a snapshot is
restored.

#### 7. Implementing `FinancialDocumentHistory`

An in-memory implementation, useful for tests:

```kotlin
class InMemoryFinancialDocumentHistory : FinancialDocumentHistory {
    private val snapshots = ConcurrentHashMap<FinancialDocumentReference, FinancialDocument>()

    fun save(document: FinancialDocument) {
        check(snapshots.putIfAbsent(document.reference, document) == null) {
            "${document.reference} already exists"   // a concurrent writer got there first
        }
    }

    override fun retrieveVersion(reference: FinancialDocumentReference) = snapshots[reference]

    override fun retrieveLatestVersion(id: UUID) =
        snapshots.values.filter { it.id == id }.maxByOrNull { it.version }
}
```

A database-backed implementation maps rows back into snapshots with the stages' `restore`
factories. This sketch uses JDBI, but any data access works:

```kotlin
class JdbiFinancialDocumentHistory(private val jdbi: Jdbi) : FinancialDocumentHistory {

    override fun retrieveVersion(reference: FinancialDocumentReference): FinancialDocument? =
        jdbi.withHandle<FinancialDocument?, Exception> { handle ->
            handle.createQuery("SELECT * FROM financial_document WHERE document_id = :id AND version = :version")
                .bind("id", reference.id)
                .bind("version", reference.version.number)
                .map { rs, _ -> toDocument(rs) }
                .findOne().orElse(null)
        }

    override fun retrieveLatestVersion(id: UUID): FinancialDocument? = TODO("ORDER BY version DESC LIMIT 1")

    private fun toDocument(rs: ResultSet): FinancialDocument {
        val id = rs.getObject("document_id", UUID::class.java)
        val version = Version.of(rs.getInt("version"))
        val lineItems = readLineItems(rs)                      // your mapping
        return when (rs.getString("stage")) {
            "ESTIMATE" -> FinancialDocument.Estimate.restore(id, version, lineItems)
            "QUOTE" -> FinancialDocument.Quote.restore(id, version, lineItems)
            "INVOICE" -> FinancialDocument.Invoice.restore(id, version, lineItems)
            else -> error("Unknown stage")
        }
    }
}

// Writing: derive the stored stage from the type, not from a status field.
fun stageOf(document: FinancialDocument): String = when (document) {
    is FinancialDocument.Estimate -> "ESTIMATE"
    is FinancialDocument.Quote -> "QUOTE"
    is FinancialDocument.Invoice -> "INVOICE"
}
```

`restore` exists only to rebuild snapshots that were produced earlier through the
lifecycle API. Its previous version is always derived from its version. Use `create` and
the lifecycle operations for new snapshots.

#### 8. Retrieving previous and latest versions

```kotlin
val history: FinancialDocumentHistory = JdbiFinancialDocumentHistory(jdbi)

val previous: FinancialDocument? = invoiceV5.retrievePreviousVersion(from = history)   // v4, one lookup
val latest: FinancialDocument? = estimateV1.retrieveLatestVersion(from = history)      // newest stored snapshot
val v2: FinancialDocument? = invoiceV5.retrieveVersion(Version.of(2), from = history)  // one lookup, skips v3 and v4
```

- `retrievePreviousVersion` looks only at `previousVersion`. For version 1 it returns `null`
  without calling the history at all. Otherwise it performs exactly one lookup, for exactly
  `(id, previousVersion)`. It never walks further back.
- `retrieveLatestVersion` delegates with the document's `id`.
- `retrieveVersion` performs one lookup for `(id, version)` and does not retrieve any
  intervening versions.
- Each helper checks that the history returned the snapshot it was asked for.

To walk back further, call `retrievePreviousVersion` again on the result. Each step is
one explicit lookup that you choose to make.

### Concurrency

The library has no locks, registries, transactions, or global state. Two processes that
both hold `ABC v7` can each compute a perfectly valid `ABC v8`. Deciding which one is
stored is the persistence layer's job, normally with a uniqueness constraint or an
optimistic-concurrency check on `(document_id, version)`. The writer that loses should
reload the latest version and reapply its change order.

### Settlement is outside the document

An invoice describes the commercial document and how it evolved. It does **not** describe
settlement. There is no `amountPaid`, `amountRefunded`, `balance`, `balanceDue`,
`paymentStatus`, `payments`, or `refunds` on any financial document, and there never will
be. Settlement is a separate bounded context that references documents by their
`FinancialDocumentReference`. This library models it in the
[payment reconciliation](#payment-reconciliation) domain, where balances are derived from
immutable payment records rather than stored. Your application still owns persistence,
processor integration, and payment policy.

### Financial API

The complete public API of `io.github.castab.commerce.financial`, without KDoc and bodies:

```kotlin
public sealed class FinancialDocument {
    public val id: UUID
    public val version: Version
    public val previousVersion: Version?
    public val lineItems: List<LineItem>
    public val currency: Currency
    public val subtotal: Money
    public val taxAmount: Money
    public val total: Money
    public val reference: FinancialDocumentReference
    public val previousReference: FinancialDocumentReference?
    public abstract fun changeOrder(changeOrder: ChangeOrder): FinancialDocument

    public class Estimate : FinancialDocument {
        override fun changeOrder(changeOrder: ChangeOrder): Estimate
        public fun toQuote(): Quote
        public companion object {
            public fun create(id: UUID, lineItems: List<LineItem>): Estimate
            public fun restore(id: UUID, version: Version, lineItems: List<LineItem>): Estimate
        }
    }

    public class Quote : FinancialDocument {
        override fun changeOrder(changeOrder: ChangeOrder): Quote
        public fun toInvoice(): Invoice
        public companion object {
            public fun create(id: UUID, lineItems: List<LineItem>): Quote
            public fun restore(id: UUID, version: Version, lineItems: List<LineItem>): Quote
        }
    }

    public class Invoice : FinancialDocument {
        override fun changeOrder(changeOrder: ChangeOrder): Invoice
        public companion object {
            public fun create(id: UUID, lineItems: List<LineItem>): Invoice
            public fun restore(id: UUID, version: Version, lineItems: List<LineItem>): Invoice
        }
    }
}

public class Version : Comparable<Version> {
    public val number: Int
    public fun next(): Version
    public companion object {
        public val INITIAL: Version
        public fun of(number: Int): Version
    }
}

public data class FinancialDocumentReference(val id: UUID, val version: Version)

public data class Money(val amount: BigDecimal, val currency: Currency) {
    public operator fun plus(other: Money): Money
    public operator fun minus(other: Money): Money
    public operator fun times(multiplier: BigDecimal): Money
    public companion object { public fun zero(currency: Currency): Money }
}

public data class LineItem(
    val id: UUID,
    val description: String,
    val subDescription: String? = null,
    val quantity: BigDecimal?,
    val price: Money,
    val taxAmount: Money,
) {
    public val currency: Currency
    public val subtotal: Money
    public val total: Money
}

public class ChangeOrder(changes: List<Change>) {
    public val changes: List<Change>
    public sealed interface Change {
        public data class AddLineItem(val lineItem: LineItem) : Change
        public data class ReplaceLineItem(val lineItemId: UUID, val replacement: LineItem) : Change
        public data class RemoveLineItem(val lineItemId: UUID) : Change
    }
}

public interface FinancialDocumentHistory {
    public fun retrieveVersion(reference: FinancialDocumentReference): FinancialDocument?
    public fun retrieveLatestVersion(id: UUID): FinancialDocument?
}

public fun FinancialDocument.retrievePreviousVersion(from: FinancialDocumentHistory): FinancialDocument?
public fun FinancialDocument.retrieveVersion(version: Version, from: FinancialDocumentHistory): FinancialDocument?
public fun FinancialDocument.retrieveLatestVersion(from: FinancialDocumentHistory): FinancialDocument?
```

From Java, the factories are static (`FinancialDocument.Quote.create(id, items)`,
`Version.of(3)`, `Version.INITIAL`), and the history helpers are static methods on
`FinancialDocumentHistories`.

## Payment reconciliation

Package `io.github.castab.commerce.payment`. Immutable records of money received, where it
was applied, how mistakes were corrected, and what was returned, plus reconciliation
derived from those records.

A financial document describes what is charged. Everything about settlement lives here, in
separate records that reference documents:

| Type | Records |
|---|---|
| `PaymentRecord` | Money received: an amount, a method, a time, and an optional processor reference. |
| `PaymentAllocation` | How much of a payment was applied to a financial document, at one exact snapshot. |
| `PaymentAllocationReversal` | A correction: some or all of an allocation was recorded in error. No money moves. |
| `RefundRecord` | Money actually returned to the payer, out of a payment. |
| `RefundAllocation` | Which allocation's applied value a refund unwinds. |
| `PaymentReconciliation` | Derived: what became of one payment's money. |
| `FinancialDocumentReconciliation` | Derived: how much is applied to a document lineage and what remains owed. |

The dependency is one-directional: `payment` references `financial`, and `financial` never
references `payment`. A `FinancialDocument` has no `amountPaid`, `balance`,
`paymentStatus`, `payments`, or `refunds`, and never will. The booking lifecycle depends on
neither.

### Records reference, never embed

Every record is immutable, and every relationship is a reference (a `UUID`, or a
`FinancialDocumentReference` for documents). No record holds another record or a document,
so each one maps onto one row or document in your store, and loading one never loads a
graph.

```text
FinancialDocument D/v3
       ↑
       │  financialDocumentReference = (D, v3)
       │
PaymentAllocation A1
       ↑
       │  paymentReference = P1
       │
Payment P1
```

A payment belongs to no document. It may be unapplied, partially applied, applied to one
document, or split across several:

```text
Payment P1
 ├── Allocation A1 → D1/v2
 └── Allocation A2 → D2/v4
```

### Exact snapshot and enduring lineage

A `PaymentAllocation` references a document with the existing
`FinancialDocumentReference(id, version)`, which carries both meanings you need without a
second identifier:

| Question | Comparison | Meaning |
|---|---|---|
| Which allocations were made against this exact snapshot? | `allocation.financialDocumentReference == FinancialDocumentReference(d, Version.of(2))` | What the commercial obligation looked like when the money was applied. |
| Which allocations belong to this document? | `allocation.financialDocumentReference.id == d` | The enduring commercial obligation, across every version. |

An allocation **stays attached to the snapshot where it occurred**. It never rolls forward
when the document advances:

```text
D/v1 Estimate
    ↓
D/v2 Quote       ← $300 deposit allocated here, and it stays here
    ↓
D/v3 Quote
    ↓
D/v4 Invoice     ← reconciled: the deposit counts, because it shares id D
```

Rewriting the deposit to `D/v4` would falsify history. Instead, lineage reconciliation of
the current snapshot finds every allocation whose reference shares the document's `id`.

### Corrections are appended, never edited

Nothing is ever edited or deleted. A mistake is corrected by adding a record:

```text
Payment P1                 $500
Allocation A1              $500 → Document X/v3
                                   (mistake discovered)
AllocationReversal R1      $500 → reverses A1
Allocation A2              $500 → Document Y/v2
```

The history still shows A1, R1, and A2. Partial reversals work the same way: reversing
$200 of a $500 allocation leaves $300 of it applied and makes $200 of the payment
available to allocate again.

### Allocation reversal versus refund

These are different facts and are never interchangeable:

```text
Allocation reversal
    = correct bookkeeping
    corrects where money was recorded as applied
    no money moves
    the reversed amount becomes unapplied and can be allocated again

Refund
    = money leaves the business
    returned to the payer

Refund allocation
    = identifies which applied value a refund unwound
    does not make any value available to allocate again
```

Never record a correction as a fake refund, and never record a refund by editing or
deleting an allocation.

A `RefundRecord` references the **payment** whose money it returns, not a document: a
refund gives back money that a payment brought in. When the refunded money had been
applied to a document, a `RefundAllocation` records which allocation it unwinds, which
completes the audit path:

```text
FinancialDocument D/v4
        ↑
PaymentAllocation A1      $500 → D/v4
        ↑
Payment P1                $500
        ↑
Refund R1                 $100 of P1
        ↓
RefundAllocation RA1      $100 of R1 unwinds A1
```

A refund allocation does **not** make value available to allocate again. The refund
reduces what the payment kept (`netReceived`), and the refund allocation reduces what is
applied (`netAllocated`) by the same amount, so `unallocated` is unchanged:

```text
                    Reversal $100        Refund $100 + RefundAllocation $100
Payment             $500                 $500
Allocated           $500                 $500
netReceived         $500                 $400
netAllocated        $400                 $400
unallocated         $100  ← reusable     $0    ← the $100 left the business
```

A refund allocation is **optional**. Money refunded from a payment's unapplied portion was
never applied to any document, so there is nothing to unwind:

```text
Payment       $500
Allocated     $300
Unapplied     $200
Refund        $100   ← from unapplied money: no RefundAllocation
```

A refund's `method` is how the money actually left, which need not be how it arrived. A
check payment refunded in cash and a debit payment refunded in cash are both recordable.
Whether your business or processor permits a combination is your policy; the library only
records what happened.

`PaymentMethod` (`CASH`, `CHECK`, `CARD`, `BANK_TRANSFER`, `DIGITAL_WALLET`, `OTHER`) names
the instrument, not the processor. The processor goes in an optional
`ExternalPaymentReference(provider, reference)` or `ExternalRefundReference(provider,
reference)`, such as `CARD` with `("provider-a", "payment-123")` or `DIGITAL_WALLET` with
`("provider-b", ...)`. The two reference types are distinct so that a payment's transaction id
cannot be recorded as a refund's. The library never interprets them and depends on no
processor SDK.

### Derived reconciliation

Balances are never stored. They are derived from the records every time, so they cannot
disagree with them:

```text
PaymentReconciliation
    netReceived   = paymentAmount - totalRefunded
    netAllocated  = grossAllocated - allocationReversals - refundAllocations
    unallocated   = netReceived - netAllocated

FinancialDocumentReconciliation
    netApplied    = grossAllocated - allocationReversals - refundAllocations
    balance       = documentTotal - netApplied
```

| Scenario | Net allocated | Net received | Unallocated |
|---|---|---|---|
| $500 payment, $500 allocated, $100 refunded with a $100 refund allocation | $400 | $400 | $0 |
| $500 payment, $300 allocated, $100 refunded from unapplied money | $300 | $400 | $100 |
| $500 payment, $500 allocated, $200 of the allocation reversed | $300 | $500 | $200 |

Document reconciliation aggregates across versions by document `id` and uses the total of
the snapshot you pass, normally the latest. With a $300 allocation to `D/v2` and a current
`D/v5` total of $1,250, the balance is $950. A negative balance means more is applied than
the document currently totals. There is no payment status: derive `UNPAID`,
`PARTIALLY_PAID`, `PAID`, or `OVERPAID` from these amounts if you need one.

The application supplies the records; nothing is loaded. The collections you pass may
contain records of other payments or documents, which are ignored. `reconcile` rejects an
inconsistent history with an `IllegalArgumentException` instead of deriving nonsense:

- a record in another currency than its payment, document, allocation, or refund;
- allocations and refunds of a payment that together exceed it (a reversal makes its
  amount unapplied and allocatable again; a refund allocation does not, because the
  refunded money has left);
- reversals of an allocation that together exceed it, or reversals and refund allocations
  that together reduce it below zero;
- refunds of a payment that together exceed it;
- refund allocations that link a refund and an allocation of different payments, or refer
  to one that was not supplied, or that together exceed their refund;
- an allocation to a later version than the snapshot being reconciled;
- a record supplied twice.

Validation looks at the supplied records as a whole and does not interpret the order of
their timestamps.

### Creation and restoration

Where a record must agree with other records, it is created from the real objects and
stores only their references, as `FinancialDocument` does with its snapshots:

| Record | `create(...)` takes | `create` checks | `restore(...)` takes |
|---|---|---|---|
| `PaymentAllocation` | `payment`, `financialDocument` | currency matches both; amount ≤ payment | `paymentReference`, `financialDocumentReference` |
| `PaymentAllocationReversal` | `allocation` | currency; amount ≤ allocation | `paymentAllocationReference` |
| `RefundRecord` | `payment` | currency; amount ≤ payment | `paymentReference` |
| `RefundAllocation` | `refund`, `allocation` | same payment; currency; amount ≤ refund and ≤ allocation | `refundReference`, `paymentAllocationReference` |

`restore` is for persistence adapters rebuilding stored records. Checks that need more than
one record (cumulative reversals, cumulative refunds, over-allocation) happen in
reconciliation, because a single record cannot see the others. `PaymentRecord` depends on
no other record and has a public constructor. Every amount must be strictly positive,
compared numerically (`0.00` is rejected). Every `id` is a `UUID` your application
supplies; the library never generates one.

The library does not restrict which stages accept money. Whether an estimate may take a
deposit, or only invoices take payment, is your policy.

### Example: deposit, later invoice version, partial refund

```kotlin
import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.payment.ExternalPaymentReference
import io.github.castab.commerce.payment.FinancialDocumentReconciliation
import io.github.castab.commerce.payment.PaymentAllocation
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentReconciliation
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.payment.RefundAllocation
import io.github.castab.commerce.payment.RefundRecord
import java.time.Instant
import java.util.UUID

val catering = LineItem(UUID.randomUUID(), "Catering", quantity = null, price = usd("1000.00"), taxAmount = usd("0.00"))
val rentals = LineItem(UUID.randomUUID(), "Table rentals", quantity = null, price = usd("200.00"), taxAmount = usd("0.00"))

// D/v1: a $1,000 quote.
val quoteV1 = FinancialDocument.Quote.create(id = UUID.randomUUID(), lineItems = listOf(catering))

// A $300 card deposit arrives through a provider and is applied to the quote as it stands.
val deposit = PaymentRecord(
    id = UUID.randomUUID(),
    amount = usd("300.00"),
    method = PaymentMethod.CARD,
    receivedAt = Instant.parse("2026-05-01T17:00:00Z"),
    externalReference = ExternalPaymentReference(provider = "provider-a", reference = "payment-123"),
)
val depositAllocation = PaymentAllocation.create(
    id = UUID.randomUUID(),
    payment = deposit,
    financialDocument = quoteV1,
    amount = usd("300.00"),
    allocatedAt = Instant.parse("2026-05-01T17:01:00Z"),
)

// The quote grows to $1,200 (D/v2) and is invoiced (D/v3).
val quoteV2 = quoteV1.changeOrder(ChangeOrder(listOf(ChangeOrder.Change.AddLineItem(rentals))))
val invoiceV3 = quoteV2.toInvoice()

FinancialDocumentReconciliation.reconcile(invoiceV3, allocations = listOf(depositAllocation))
    .balance                                     // 900.00 USD
depositAllocation.financialDocumentReference     // still (D, v1)

// $100 of the deposit is returned in cash, unwinding part of the deposit allocation.
val refund = RefundRecord.create(
    id = UUID.randomUUID(),
    payment = deposit,
    amount = usd("100.00"),
    method = PaymentMethod.CASH,                 // may differ from the payment's method
    refundedAt = Instant.parse("2026-05-20T12:00:00Z"),
)
val unwound = RefundAllocation.create(
    id = UUID.randomUUID(),
    refund = refund,
    allocation = depositAllocation,
    amount = usd("100.00"),
    allocatedAt = Instant.parse("2026-05-20T12:00:00Z"),
)

val invoice = FinancialDocumentReconciliation.reconcile(
    document = invoiceV3,
    allocations = listOf(depositAllocation),
    refundAllocations = listOf(unwound),
)
invoice.grossAllocated                           // 300.00 USD
invoice.refundAllocations                        // 100.00 USD
invoice.netApplied                               // 200.00 USD
invoice.balance                                  // 1000.00 USD

val payment = PaymentReconciliation.reconcile(
    payment = deposit,
    allocations = listOf(depositAllocation),
    refunds = listOf(refund),
    refundAllocations = listOf(unwound),
)
payment.netReceived                              // 200.00 USD
payment.netAllocated                             // 200.00 USD
payment.unallocated                              // 0.00 USD
```

Every record from that history still exists, unchanged: the deposit, its allocation to
`D/v1`, the refund, and the refund allocation, alongside `D/v1`, `D/v2`, and `D/v3`.

### Persistence and scope

Persistence stays in your application. A relational store would naturally hold one table
per record type (`payments`, `payment_allocations`, `payment_allocation_reversals`,
`refunds`, `refund_allocations`), with references as foreign keys and uniqueness of
external references enforced there if you want it. The library ships no schema,
repository, entity, or provider adapter implementation.

The payment domain records and reconciles actual payment and refund facts. It is not an
accounting ledger: there are no accounts, journals, debits, credits, or posting periods.
It also does not model store or customer credit, gift cards, credit memos, chargebacks,
disputes, authorization and capture, processor fees, tips, payouts, settlement batches,
bank reconciliation, currency conversion, card data, or checkout UI. Business policy (who may
pay what, refund windows, refund-to-original-method rules, deposit percentages) stays in
your application.

### Payment API

The complete public API of `io.github.castab.commerce.payment`, without KDoc and bodies:

```kotlin
public enum class PaymentMethod { CASH, CHECK, CARD, BANK_TRANSFER, DIGITAL_WALLET, OTHER }

public data class ExternalPaymentReference(val provider: String, val reference: String)
public data class ExternalRefundReference(val provider: String, val reference: String)

public class PaymentRecord(
    public val id: UUID,
    public val amount: Money,
    public val method: PaymentMethod,
    public val receivedAt: Instant,
    public val externalReference: ExternalPaymentReference? = null,
) {
    public val currency: Currency
}

public class PaymentAllocation {
    public val id: UUID
    public val paymentReference: UUID
    public val financialDocumentReference: FinancialDocumentReference
    public val amount: Money
    public val allocatedAt: Instant
    public val currency: Currency
    public companion object {
        public fun create(id: UUID, payment: PaymentRecord, financialDocument: FinancialDocument, amount: Money, allocatedAt: Instant): PaymentAllocation
        public fun restore(id: UUID, paymentReference: UUID, financialDocumentReference: FinancialDocumentReference, amount: Money, allocatedAt: Instant): PaymentAllocation
    }
}

public class PaymentAllocationReversal {
    public val id: UUID
    public val paymentAllocationReference: UUID
    public val amount: Money
    public val reversedAt: Instant
    public val reason: String?
    public val currency: Currency
    public companion object {
        public fun create(id: UUID, allocation: PaymentAllocation, amount: Money, reversedAt: Instant, reason: String? = null): PaymentAllocationReversal
        public fun restore(id: UUID, paymentAllocationReference: UUID, amount: Money, reversedAt: Instant, reason: String? = null): PaymentAllocationReversal
    }
}

public class RefundRecord {
    public val id: UUID
    public val paymentReference: UUID
    public val amount: Money
    public val method: PaymentMethod
    public val refundedAt: Instant
    public val externalReference: ExternalRefundReference?
    public val currency: Currency
    public companion object {
        public fun create(id: UUID, payment: PaymentRecord, amount: Money, method: PaymentMethod, refundedAt: Instant, externalReference: ExternalRefundReference? = null): RefundRecord
        public fun restore(id: UUID, paymentReference: UUID, amount: Money, method: PaymentMethod, refundedAt: Instant, externalReference: ExternalRefundReference? = null): RefundRecord
    }
}

public class RefundAllocation {
    public val id: UUID
    public val refundReference: UUID
    public val paymentAllocationReference: UUID
    public val amount: Money
    public val allocatedAt: Instant
    public val currency: Currency
    public companion object {
        public fun create(id: UUID, refund: RefundRecord, allocation: PaymentAllocation, amount: Money, allocatedAt: Instant): RefundAllocation
        public fun restore(id: UUID, refundReference: UUID, paymentAllocationReference: UUID, amount: Money, allocatedAt: Instant): RefundAllocation
    }
}

public class PaymentReconciliation {
    public val paymentReference: UUID
    public val paymentAmount: Money
    public val totalRefunded: Money
    public val netReceived: Money
    public val grossAllocated: Money
    public val allocationReversals: Money
    public val refundAllocations: Money
    public val netAllocated: Money
    public val unallocated: Money
    public val currency: Currency
    public companion object {
        public fun reconcile(
            payment: PaymentRecord,
            allocations: Collection<PaymentAllocation>,
            allocationReversals: Collection<PaymentAllocationReversal> = emptyList(),
            refunds: Collection<RefundRecord> = emptyList(),
            refundAllocations: Collection<RefundAllocation> = emptyList(),
        ): PaymentReconciliation
    }
}

public class FinancialDocumentReconciliation {
    public val documentReference: FinancialDocumentReference
    public val documentTotal: Money
    public val grossAllocated: Money
    public val allocationReversals: Money
    public val refundAllocations: Money
    public val netApplied: Money
    public val balance: Money
    public val currency: Currency
    public companion object {
        public fun reconcile(
            document: FinancialDocument,
            allocations: Collection<PaymentAllocation>,
            allocationReversals: Collection<PaymentAllocationReversal> = emptyList(),
            refundAllocations: Collection<RefundAllocation> = emptyList(),
        ): FinancialDocumentReconciliation
    }
}
```

The records are regular classes, not data classes: there is no `copy()` to invite editing
a historical fact. From Java, `create`, `restore`, and `reconcile` are static methods, with
overloads for the optional parameters.

## Principal authorization

Package `io.github.castab.commerce.staff` distinguishes a human staff `User` from a
non-human `ServiceIdentity`, such as an adapter or worker. Both implement `Principal`:
an entity with an ID, `ACTIVE` or `DISABLED` status, and role assignments. `User` keeps
human fields such as username and names; `ServiceIdentity` has a service name. Their
UUID-backed `UserId` and `ServiceId` are distinct types implementing `PrincipalId`.
Neither model contains credentials or sessions. The existing `UserStatus` name remains
as a Kotlin alias for the shared `PrincipalStatus`.

```text
authenticate caller → PrincipalId → resolve Principal → assigned RoleKey
                  → RoleDefinition → granted PermissionKey → authorize operation
```

`RoleKey` and `PermissionKey` are validated, open-ended strings, not enums. Roles are
shared by humans and services. `CommerceRoles` (`Administrator`, `Manager`,
`Supervisor`, `Employee`) provides conventional keys without built-in grants.
`CommercePermissions` provides keys for booking read/modify, financial-document
read/create, payment/refund recording, and user read/manage and role assignment.
Applications define the actual role bundles and may add their own, for example:

```kotlin
import io.github.castab.commerce.staff.*
import java.util.UUID

// Application-defined example keys; the "example." prefix stands for your own namespace.
val MessageRespond = PermissionKey("example.message.respond")
val Support = RoleDefinition(
    key = RoleKey("example.support"),
    displayName = "Support",
    description = "Responds to incoming messages",
    permissions = setOf(MessageRespond),
)

val StripeAdapterRole = RoleDefinition(
    key = RoleKey("commerce.payment-reporter"),
    displayName = "Payment Reporter",
    description = "Reports externally processed payments and refunds",
    permissions = setOf(
        CommercePermissions.PaymentRecord,
        CommercePermissions.RefundRecord,
    ),
)
val stripeAdapter = ServiceIdentity(
    id = ServiceId(UUID.randomUUID()),
    name = "stripe-adapter",
    status = PrincipalStatus.ACTIVE,
    roles = setOf(RoleAssignment(StripeAdapterRole.key)),
)

val principalResolver = PrincipalResolver { id ->
    when (id) {
        is UserId -> usersById[id]
        is ServiceId -> servicesById[id]
    }
}
val roleResolver = RoleResolver { key -> rolesByKey[key] }
val permissionResolver = RoleBasedPermissionResolver(principalResolver, roleResolver)

stripeAdapter.id.can(CommercePermissions.PaymentRecord, permissionResolver) // true
stripeAdapter.id.can(CommercePermissions.UserManage, permissionResolver)    // false
userId.can(MessageRespond, permissionResolver)                              // same API for a human
```

Here `usersById`, `servicesById`, and `rolesByKey` represent application-owned sources,
with `rolesByKey` containing the example definitions. The resolver ports specify no
database, protocol, or cache. `UserResolver` remains available for human-specific
lookups; authorization uses `PrincipalResolver` and `PermissionResolver`, both of which
operate on `PrincipalId`. A business operation checks a `PermissionKey`, not a role or
principal type: any number of roles can grant the same capability to humans or services.

`RoleBasedPermissionResolver` unions the permissions of all resolved assigned roles.
It grants nothing for an unknown or disabled principal, or when a resolver returns a
different identity. It skips missing role definitions and definitions whose key does
not match the requested role; other valid roles can still grant permissions. An absent
permission is denied. There is no trusted-service bypass, explicit denial, or role
precedence. `PrincipalId.can(permission, permissionResolver)` keeps the dependency
explicit; there is no global authorization state.

For an HTTP application, the usual boundary is:

```text
browser request → validate session credential ─→ UserId ────┐
service request → validate service credential ─→ ServiceId ─┤
                                                   PrincipalId
                                                       ↓
                                     check required permission
                                                       ↓
                                          allow or deny operation
```

The application may respond `401` when authentication fails and `403` when an
authenticated principal lacks permission. Expired, revoked, or otherwise invalid
credentials never reach commerce authorization. The library neither validates
credentials nor returns HTTP status codes. Role scoping and explicit deny policies
are outside this version. A future audit model may use `PrincipalId` for `recordedBy`
or `performedBy`, preserving the distinction between `UserId` and `ServiceId`;
existing commerce records are unchanged.

## Payment adapter contract

Package `io.github.castab.commerce.payment.adapter` defines the semantic boundary between
a consuming application and external payment providers. It does not define transport. An adapter
authenticates provider input, translates it to these models, and may be stateless.
The consuming application remains the durable source of truth: `PaymentRecord` means money arrived and
`RefundRecord` means money left. A prepared checkout, failure observation, or provider
event does not itself create either fact. An adapter can run as a serverless function,
service, monolith component, message consumer, or another arrangement.

The existing `ExternalPaymentReference` and `ExternalRefundReference` identify
provider-side payment and refund objects. Their `provider` strings match
`PaymentProviderId.value`. `ProviderEventReference(provider, eventId)` identifies an
**event**, not the provider-side object: one object may generate many events. The
adapter contract does not require retaining raw provider payloads.

### Payment preparation

The consuming application creates an `AuthorizedPayment(paymentId, provider, amount)` after deciding the
authoritative amount. The UI and adapter never calculate or override it. For an adapter
with `PAYMENT_INITIATION`, it sends `PreparePayment(authorizedPayment)`. A
`PaymentPrepared` carries the same application-supplied UUID, the external payment reference, and
optionally a hosted checkout URI and expiry. `assessPreparation` checks identity,
provider, and capabilities. An observation-only provider needs no preparation step;
the application can still use `AuthorizedPayment` to validate its eventual observation.

```text
Application -- PreparePayment(P123, 500 USD) --> Adapter -- provider operation --> Provider
Application <-- PaymentPrepared(P123, provider reference, optional checkout URI) -- Adapter
```

`PaymentAdapterCapabilities` is a snapshot of supported operations:
`PAYMENT_INITIATION`, `HOSTED_CHECKOUT`, `ASYNC_PAYMENT_CONFIRMATION`, `REFUNDS`, and
`PARTIAL_REFUNDS`. An empty set is valid for an observation-only adapter. Hosted checkout
details are optional even when supported. Check `assessRefundRequest` before asking an
adapter to refund; a provider without `REFUNDS` or `PARTIAL_REFUNDS` receives an explicit
unsupported result. Capability checks govern outbound requests. An authenticated success
observation is still checked against the application's payment facts even if capability advertising later
changes.

### Provider observations and idempotency

```text
Provider -- event --> Adapter -- verify authenticity; translate --> Application
Application -- assessPaymentSuccess(P123, provider reference, event, amount) -->
    Accepted(PaymentRecord, ProviderEventReceipt) | AlreadyProcessed | Rejected(reason)
```

The adapter must verify its provider-specific input before producing a trusted contract
object. The consuming application separately validates business integrity: the payment UUID,
provider, exact currency, numerically equal authorized amount, optional prepared
reference, and existing records. A trusted observation is not automatically accepted.
`PaymentFailed` produces no payment record. A completed payment cannot be replaced by a
failure or a second success. The application owns any pending request status; these
messages do not add a second lifecycle to `PaymentRecord`.

The application supplies relevant records and receipts to the pure
`PaymentAdapterProcessing` functions. For an accepted event, persist the returned record
and receipt in **one transaction**. Enforce unique application-owned payment/refund IDs, unique
provider object references, and unique `(provider, eventId)` receipts in that storage:

```text
BEGIN
  insert receipt, unique(provider, eventId)
  insert PaymentRecord or RefundRecord (or update application request status for failure)
COMMIT

Provider retries the same event --> AlreadyProcessed; no duplicate financial effect
```

The caller must recheck under its transaction's concurrency controls; an earlier pure
decision cannot itself reserve an event ID. Reusing the same event ID for another
payment or refund target is rejected as `EVENT_CONFLICT`. An unexpected storage or provider failure
remains an application concern. Do not acknowledge a provider event as processed when
the application's transaction failed if the provider can retry delivery.

### Refunds

```text
Application -- RequestRefund(refundId, paymentId, provider payment reference, amount) --> Adapter
Adapter -- provider-specific refund operation --> Provider
Provider -- event --> Adapter -- verify; translate --> RefundSucceeded or RefundFailed
Application -- assessRefundSuccess --> Accepted(RefundRecord, ProviderEventReceipt)
```

The consuming application supplies the refund UUID and amount. The request references a recorded payment and
its external payment object. `assessRefundRequest` checks the provider's capability,
payment identity, currency, and cumulative refundable amount. `assessRefundSuccess`
also checks the actual amount and refund reference before creating a `RefundRecord`.
Partial refunds are supported up to the remaining payment amount; the existing
`PaymentReconciliation` still validates complete payment, allocation, and refund
history. `RefundFailed` creates no refund record. The application supplies prior refunds
and receipts and persists each accepted result atomically. No adapter database is
required.

These types contain no provider SDK, webhook format, HTTP route, message topic,
serialization framework, credential, or persistence implementation. A future adapter
can use any transport while preserving these meanings.

## Using both domains together

The booking lifecycle and financial document stages are independent and compose in
application code. A booking's `Quote` *phase* and a financial `Quote` *document* are
different concepts: one says where the booking stands, and the other is the priced offer.
An application can let its phase models carry documents. `BookingId` is the application's
own type, as in [Implementing the lifecycle](#implementing-the-lifecycle):

```kotlin
import io.github.castab.commerce.booking.lifecycle.BookingLifecycle
import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument

data class CateringQuote(
    val bookingId: BookingId,
    val quote: FinancialDocument.Quote,
) : BookingLifecycle.Active.Quote {

    // A document revision stays within the booking's Quote phase.
    fun revise(changeOrder: ChangeOrder): CateringQuote = copy(quote = quote.changeOrder(changeOrder))

    override fun toBooking(): CateringBooking = CateringBooking(bookingId, invoice = quote.toInvoice())

    override fun cancel(): DeclinedCateringQuote = DeclinedCateringQuote(bookingId, declined = quote.reference)
}

data class CateringBooking(
    val bookingId: BookingId,
    val invoice: FinancialDocument.Invoice,
) : BookingLifecycle.Active.Booked {

    // Invoice change orders stay within the booking's Booked phase.
    fun applyChangeOrder(changeOrder: ChangeOrder): CateringBooking = copy(invoice = invoice.changeOrder(changeOrder))

    override fun complete(): CompletedCateringBooking = CompletedCateringBooking(bookingId, invoice.reference)

    override fun cancel(): CancelledCateringBooking = CancelledCateringBooking(bookingId, invoice.reference)
}
```

The booking lifecycle still owns no financial data, and the financial documents hold no
booking or customer data. The relationship between them lives in the application's own
model (here, the phase types hold the documents). Whether the two advance together, and
when, is your application's decision.

## What this library is not

It is not:

- an ORM, a repository layer, or a persistence framework;
- a workflow engine or a runtime policy engine;
- a booking workflow engine or repository;
- a pricing, tax-calculation, or quote engine (your application prices lines and computes tax);
- a payment processor integration, a checkout, or a card-data store;
- an authentication or session system;
- an accounting ledger (no accounts, journals, debits, or credits);
- a serialization format or a framework integration;
- a state enum wrapper.

## Requirements

| | Version | Notes |
|---|---|---|
| Java | **25** | Hard requirement. The bytecode targets Java 25 (class file version 69). The build uses a Java 25 toolchain and fails if none is installed, because auto-download is disabled. |
| Kotlin | 2.4.20 | Compiler and Gradle plugin used to build the library. `kotlin-stdlib` 2.4.20 is the only runtime dependency. |
| Gradle | 9.7.0 | Pinned through the wrapper (with checksum), used to build this repository. It is the newest Gradle that Kotlin 2.4.20 declares full support for. You don't need Gradle to *consume* the library. |

The Java 25 requirement is intentional. Do not expect a build targeting 17 or 21.

Versions are declared in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml). The
module build is [`domain/build.gradle.kts`](build.gradle.kts), and the conventions it
shares with `:runtime` are in the root [`build.gradle.kts`](../build.gradle.kts).

## Building and testing

From the repository root:

```bash
./gradlew :domain:build
```

On Windows:

```powershell
.\gradlew.bat :domain:build
```

`:domain:build` compiles the library, runs its test suite and ktlint checks, and assembles
the main, sources, and javadoc jars. It also runs `verifyRuntimeDependencies`, which fails
if the module's runtime classpath ever contains anything beyond `kotlin-stdlib`. It needs
neither Docker nor a database, and it does not build `:runtime`. It publishes nothing and
needs no GitHub credentials. Local builds use the version `0.0.0-SNAPSHOT`.

Tests are written with [Kotest](https://kotest.io) 6.2.5 (`FunSpec`, Kotest assertions) on
the JUnit Platform. They use concrete test-owned fixtures and real value objects for domain
behavior, and [MockK](https://mockk.io) 1.14.11 where a collaborator is worth mocking: the
booking phase interfaces and `FinancialDocumentHistory`. MockK works on Java 25 as
resolved, with Byte Buddy 1.18.2, and needs no dependency override. Test dependencies
are not part of the library's published runtime or API dependencies.

The build cache is enabled. To make the tests run again rather than reuse cached results,
add `--no-build-cache` (or run `./gradlew :domain:test --rerun`). Formatting, CI, and the
whole-project build are described in the [root README](../README.md#building-and-testing).

## Releasing

`commerce-domain` is released together with `commerce-runtime` from one GitHub Release. See
[Releasing](../README.md#releasing). **Published versions are immutable.** If `0.0.1` has
a problem, fix it and release `0.0.2`.

## Current scope

What exists today:

- the booking lifecycle protocol: 3 sealed classifications, 5 open phase interfaces, and
  6 abstract transition functions;
- the financial document domain: the sealed `FinancialDocument` with `Estimate`, `Quote`,
  and `Invoice`, plus `Version`, `Money`, `LineItem`, `ChangeOrder`,
  `FinancialDocumentReference`, and the `FinancialDocumentHistory` SPI with its lookup
  helpers;
- the payment reconciliation domain: `PaymentRecord`, `PaymentAllocation`,
  `PaymentAllocationReversal`, `RefundRecord`, `RefundAllocation`, `PaymentMethod`, the
  external reference types, and the derived `PaymentReconciliation` and
  `FinancialDocumentReconciliation`;
- the provider-neutral payment adapter contract for preparing payments, observing
  provider events, deciding payment and refund effects, and tracking event receipts;
- the staff domain: human `User` and non-human `ServiceIdentity` principals, distinct
  UUID-backed IDs, extensible roles and permissions, resolver ports,
  `RoleBasedPermissionResolver`, and the `PrincipalId.can` extension;
- KDoc on every public declaration;
- Kotest suites for every domain, using real fixtures and value objects, with MockK for
  mockable collaborators.

What does not exist here: persistence implementations, serialization, events, framework
integrations, payment-processor integrations, store credit, accounting ledgers, Maven
Central publishing.

## Relationship to commerce-runtime

```text
commerce-domain                  (this artifact)
      │
      ▼
commerce-runtime                 (opinionated runtime machinery, a library)
      │
      ▼
concrete commerce application    (owns main() and the process)
```

The machinery for assembling applications on these types lives in the sibling
[`commerce-runtime`](../runtime/README.md) artifact: HTTP on http4k and Jetty,
PostgreSQL persistence, transactions, configuration, and orchestration. Concrete
applications depend on `commerce-runtime`. The dependency points one way:
`commerce-runtime` depends on `commerce-domain`, and `commerce-domain` never depends on
`commerce-runtime`.

Some relationships only coordinate concepts that are each meaningful on their own, such
as which financial document belongs to which booking or customer. The concrete application
owns those, not this library and not `commerce-runtime`, which lets application
persistence share its transactions. The domain semantics documented here stay
persistence-agnostic. Persistence adapters build on them without changing the model.

Publishing to Maven Central may be added as an additional distribution channel. If it is,
GitHub Packages will keep working for existing consumers.

For contributors and coding agents: the architectural rules for changing this repository
are in [`AGENTS.md`](../AGENTS.md).

## License

Licensed under the [Apache License, Version 2.0](../LICENSE).
