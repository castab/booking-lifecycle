# Commerce Platform Architecture

## Purpose

This repository provides reusable commerce domain vocabulary and runtime capabilities for applications built on top of the platform.

Its architecture is intentionally divided between:

- `commerce-domain`: reusable commerce concepts, invariants, and semantics.
- `commerce-runtime`: reusable operational capabilities that persist, transact on, authorize, expose, and coordinate those concepts.
- applications: business-specific workflow, policy, composition, presentation, and integration.

This document defines the architectural constraints governing those boundaries.

It is not an implementation history and should not describe every feature. It exists to help contributors and agents make consistent decisions when the correct placement or shape of a change is ambiguous.

---

# 1. Semantics Determine Ownership

Architectural ownership follows the meaning of a concept, not the repository that happens to need it first.

## `commerce-domain`

`commerce-domain` owns reusable commerce vocabulary and invariants.

Examples include concepts such as:

- financial documents;
- financial line items;
- payments;
- payment allocations;
- refunds;
- refund allocations;
- deposit requirements;
- offering and catalog vocabulary;
- reconciliation semantics;
- commerce identities and references.

Domain code must remain independent of:

- HTTP;
- databases;
- authentication mechanisms;
- application-specific workflows;
- UI concerns;
- deployment infrastructure.

A concept belongs in the domain when it represents complete commerce meaning independent of any particular application.

## `commerce-runtime`

`commerce-runtime` operationalizes shared commerce concepts.

It may own reusable capabilities such as:

- persistence;
- transactions;
- migrations;
- repositories;
- ledger operations;
- sessions;
- authorization infrastructure;
- reusable HTTP capabilities;
- OpenAPI definitions for reusable capabilities;
- coherent and bulk read models;
- concurrency controls.

Runtime should implement domain semantics, not redefine them.

If runtime persistence or HTTP concerns appear to require new business meaning, first determine whether the missing concept belongs in `commerce-domain`.

## Applications

Applications own application-specific workflow and policy.

Examples include:

- inquiry workflows;
- application-specific customer entities;
- business-specific pricing policy;
- how shared commerce capabilities are composed;
- presentation and UI-facing language;
- application authentication experience;
- application-specific business operations.

Applications consume shared commerce capabilities. They must not redefine shared commerce concepts merely because a local implementation would be convenient.

---

# 2. Dependencies Point Downward

Dependencies follow this direction:

```text
Application
    ↓
commerce-runtime
    ↓
commerce-domain
```

Applications may depend on runtime and domain.

Runtime may depend on domain.

Domain must never depend on runtime or on an application.

Shared layers must not acquire knowledge of a specific application, business, UI framework, deployment environment, or workflow.

---

# 3. Generalize Complete Concepts, Not Hypothetical Reuse

A concept does not belong in the shared platform merely because another application might use it someday.

Move a concept downward when its semantics are inherently reusable and coherent without the originating application.

Prefer explicit commerce concepts over generic abstraction machinery.

Avoid abstractions such as:

- arbitrary metadata-based business models;
- universal configurable rule engines;
- generic "thing" models that erase domain meaning;
- abstractions whose primary justification is imagined future reuse.

The goal is reusable commerce vocabulary, not a universal business DSL.

---

# 4. Prefer Facts Over Mutable State

Where practical, durable commerce behavior should be represented by authoritative facts from which useful state can be derived.

Examples include:

- financial document revisions;
- payments;
- allocations;
- refunds;
- refund allocations;
- deposit requirement revisions;
- catalog revisions.

Avoid persisting mutable summaries such as:

- `paid = true`;
- `depositSatisfied = true`;
- mutable balance status;
- mutable reconciliation state;

when those values can be correctly derived from authoritative facts.

Derived state may be exposed through read models for convenience, but it must not become a competing source of truth.

---

# 5. Preserve Historical Explainability

The system should be able to answer both:

1. **What is true now?**
2. **How did it become true?**

Historical commerce facts should not be silently rewritten.

Corrections, replacements, withdrawals, reversals, and similar changes should preserve the prior facts necessary to explain the current state.

This principle is especially important for financial documents, payments, refunds, allocations, deposit requirements, and catalog revisions.

---

# 6. Financial Documents Are Immutable Business Facts

Financial documents and their established revisions are historical business facts.

Examples include:

- estimates;
- quotes;
- invoices;
- change orders or equivalent revisions.

Once established, a financial document should not be mutated in place to represent materially different commercial terms.

Changes should produce new facts or revisions while preserving provenance.

Financial document lifecycle must remain distinct from broader booking or fulfillment workflow.

An application may legitimately begin a financial lineage at different document types where its business policy permits it. The shared model must not require every invoice to originate from a quote or every quote to originate from an estimate.

---

# 7. Pricing Is Server-Authoritative

Clients express intent.

The server determines authoritative:

- prices;
- line items;
- totals;
- financial document contents;
- catalog provenance;
- pricing provenance.

Clients must not submit authoritative financial values merely because those values were displayed in a UI.

Catalogs may describe reusable price forms such as fixed, per-quantity, or per-duration pricing.

Application-specific business policy interprets catalog data in business context and produces authoritative financial artifacts.

The shared platform should not become a generic pricing-rule language solely to move application policy into shared code.

---

# 8. Catalog History Must Not Rewrite Financial History

Catalogs and offerings may evolve over time.

Financial artifacts created from catalog data must preserve the meaning and price that were authoritative when the artifact was created.

Changing:

- an offering price;
- an offering description;
- availability;
- a catalog revision;

must not retroactively alter existing estimates, quotes, invoices, or other historical financial artifacts.

Financial line items should therefore contain the durable snapshot and provenance necessary to remain independently meaningful.

---

# 9. Payments, Allocations, and Refunds Are Distinct Facts

A payment answers:

> What money was received?

An allocation answers:

> Where was that money applied?

These are distinct concepts.

Receiving payment does not inherently mean that all or part of that payment has been allocated to a particular financial document.

Refunds are also distinct positive facts.

A refund should not be modeled as:

- a negative payment;
- mutation of the original payment;
- deletion of prior allocation history.

Refund allocation may reverse or reduce the economic effect of prior allocation while preserving the underlying facts.

Reconciliation and balances should be derived from these facts.

---

# 10. Deposit Requirements Are Requirements, Not Payment State

A deposit requirement describes what must be satisfied.

It is not itself a mutable payment-status record.

A requirement should preserve the terms and financial basis against which it was established.

Replacement, withdrawal, or reactivation should preserve requirement lineage rather than rewriting prior requirements.

Whether a requirement is satisfied should be derived from authoritative payment, allocation, refund, and reconciliation facts.

---

# 11. Persistence Follows Domain Meaning

Relational decomposition is not an architectural objective by itself.

A table or relationship should exist when it represents a meaningful independent fact, lifecycle, identity, or relationship.

Do not normalize every nested structure simply because relational decomposition is possible.

Aggregate snapshot persistence, including structured persistence such as JSONB, is appropriate when the aggregate itself is the meaningful business fact and decomposing it provides no useful independent semantics.

The operational store primarily needs to answer:

> What is the authoritative state of this thing?

It should also retain sufficient history and provenance to answer:

> How did this state arise?

Analytics requirements alone should not distort the operational domain model.

---

# 12. Shared State Must Have One Owner

Applications must not mirror runtime-owned state merely for convenient reads.

Avoid application-local copies of:

- payment state;
- reconciliation state;
- financial histories;
- permission catalogs;
- deposit requirement state;
- shared catalog state;

when runtime remains the authoritative owner.

If an application needs a convenient or efficient view of runtime-owned information, add an appropriate reusable runtime read capability rather than creating a second source of truth.

Applications must not directly query runtime-owned persistence structures.

Runtime persistence is an implementation boundary.

---

# 13. Transactions Are Composable

Shared runtime operations should support caller-owned transactions where multiple operations must succeed or fail as one business unit.

Runtime APIs should not force hidden independent transactions when doing so prevents correct composition.

An application may coordinate application-owned persistence and runtime-owned commerce mutations within a shared transactional boundary when the architecture supports it.

Transaction ownership must be explicit.

---

# 14. Consistency and Concurrency Are Intentional

Isolation levels should reflect business semantics rather than database defaults alone.

Use stronger coherent snapshots where a multi-read operation must observe one logical state.

Use appropriate locking, expected-version checks, lineage constraints, predecessor constraints, or similar mechanisms where concurrent mutations may conflict.

Stale writers should fail deterministically rather than silently overwrite valid work.

Concurrency conflicts should surface as meaningful runtime/domain conflicts, not as accidental last-write-wins behavior.

---

# 15. Coherent Reads Should Observe One Logical Snapshot

When a business operation or read model requires several related documents, lineages, requirements, allocations, or other facts, prefer:

- set-based reads;
- bounded query counts;
- one explicit transaction;
- one coherent database snapshot;

over sequences of unrelated point reads that may observe different states.

Bulk APIs should preserve clear semantics around missing, duplicate, or invalid identifiers rather than silently producing ambiguous results.

---

# 16. Authorization Is Separate From Authentication

Authentication answers:

> Who is this principal?

Authorization answers:

> What may this principal do?

The shared runtime may own reusable:

- principals;
- users;
- service identities;
- sessions;
- roles;
- permissions;
- assignments;
- access-control mechanisms.

Applications may own their credential verification and login experience while using shared runtime authorization capabilities.

Avoid authorization models based on:

- hard-coded `isAdmin` flags;
- frontend-only role names;
- wildcard permissions;
- duplicated application-local RBAC infrastructure.

Authorization decisions should be explicit and permission-driven.

---

# 17. Current Permissions and Authorization Administration Are Different Concerns

A current-principal capability such as a `/me` representation answers:

> What can this principal do?

An authorization directory answers:

> What users, roles, permissions, and assignments exist and may be administered?

These serve different purposes.

Administrative interfaces must be able to represent roles and permissions that were not known to the frontend at compile time.

Do not assume the application's static knowledge of authorization entities is the complete authorization model.

---

# 18. Reusable HTTP Capabilities May Live in Runtime

Where a capability is genuinely reusable, runtime may expose reusable HTTP routes and OpenAPI definitions around it.

Examples may include shared:

- authorization;
- catalog;
- financial;
- administrative;

capabilities.

Applications decide how those capabilities are composed and exposed as part of their deployed system.

Applications should not rebuild a reusable runtime HTTP capability solely to rename or locally re-own the concept.

At the same time, application business endpoints should express application business intent rather than exposing raw low-level runtime orchestration to clients.

---

# 19. HTTP and OpenAPI Are Contracts

HTTP behavior is part of the platform contract.

Treat as intentional:

- paths;
- methods;
- request schemas;
- response schemas;
- error semantics;
- authentication requirements;
- permission requirements;
- operation IDs;
- documented behavior.

Generated OpenAPI is not incidental output.

Changes to these surfaces should be reviewed as contract changes.

Operation IDs must remain meaningful and unique.

---

# 20. UI Requirements May Reveal Missing Domain Concepts

A UI need does not automatically justify a UI-specific model.

When a screen requires new information, first determine whether the requirement exposes:

- a missing shared commerce concept;
- a missing runtime read model;
- an application-specific projection;
- presentation-only state.

Do not allow presentation needs to silently redefine the domain.

For example, if a dashboard requires a concept such as deposit requirement status, first determine whether the shared platform is missing a deposit requirement or reconciliation concept before introducing an application-local substitute.

---

# 21. Strict Domain States Are Preferred

When absence is not a legitimate domain state, prefer a required value over a nullable representation.

Do not introduce optionality solely because:

- implementation is easier;
- migration is easier;
- an intermediate state happens to exist during construction.

Types and persistence constraints should reflect actual domain validity.

---

# 22. Current Pre-Production Migration Policy

The platform is currently in a stage where production data compatibility is not required.

During this stage, prefer the correct target model over transitional compatibility scaffolding.

It is acceptable to:

- drop unused structures;
- rebuild tables;
- make fields non-null immediately;
- remove obsolete compatibility paths;
- replace incorrect schema designs directly.

Do not accumulate deprecated or dual-path designs solely to simulate production migration constraints that do not yet exist.

This is an operational policy, not a permanent architectural principle.

Once production data must be preserved, migration strategy must change accordingly.

---

# 23. Applications Compose Shared Capabilities

Applications should generally use shared capabilities rather than recreating them.

The expected relationship is:

```text
Shared commerce concept
        ↓
Reusable runtime capability
        ↓
Application composition and policy
        ↓
Application HTTP/BFF
        ↓
UI
```

Do not invert that relationship merely because a UI or application feature was the first place a missing capability became visible.

---

# 24. Decision Test for New Concepts

Before introducing a new concept or major behavior, answer the following:

1. Is this reusable commerce vocabulary, reusable operational machinery, or application-specific policy?
2. Would this concept still make sense if the originating application did not exist?
3. Is this an authoritative business fact or something derivable from existing facts?
4. Am I creating application-local state merely because the application needs a convenient read?
5. Does the design preserve historical explainability?
6. Does relational decomposition reflect meaningful independent facts, or only implementation preference?
7. Does this abstraction exist because semantics demand it, or because future reuse is imaginable?
8. Does the change unintentionally alter an API, authorization, persistence, transaction, or concurrency contract?
9. Can the operation participate correctly in a larger transaction?
10. Would a different application reasonably consume this capability without inheriting assumptions from the originating application?

If ownership remains unclear after applying these questions, surface the ambiguity rather than resolving it through the smallest local implementation.

---

# 25. Architectural Conflict Handling

This document defines architectural constraints rather than suggestions.

When requested work appears to conflict with these principles:

1. identify the conflict explicitly;
2. determine whether the requested behavior reflects an intentional architecture change;
3. avoid introducing a local workaround that preserves the immediate feature while violating the shared model;
4. update this document or create an ADR if the architecture is intentionally changed.

Code should follow architecture.

Architecture should not silently change because a local implementation was easier.

---

# 26. ADRs

This document defines durable platform-wide principles.

Use an Architecture Decision Record when a specific decision requires additional explanation, especially when:

- reasonable alternatives existed;
- the selected approach has non-obvious tradeoffs;
- a future contributor may be tempted to reverse the decision;
- implementation details are too specific for this document.

ADRs should explain the context, decision, consequences, and rejected alternatives.

They should complement this document rather than repeat it.

---

# Summary

The platform follows several recurring ideas:

**Semantics determine ownership.**

Shared commerce meaning belongs in the domain. Reusable operational machinery belongs in runtime. Application workflow and policy remain application-owned.

**Facts are preferred over mutable state.**

Preserve authoritative business facts and derive useful current state whenever practical.

**History must remain explainable.**

Current truth must not require erasing the facts that produced it.

**Applications compose rather than duplicate.**

Shared capabilities should have one authoritative owner and be consumed through deliberate runtime contracts.

**Architecture wins over local convenience.**

When a feature exposes a missing shared concept, resolve the concept at the correct layer rather than hiding the problem inside the application.