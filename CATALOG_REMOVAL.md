# Catalog-independent finance: breaking migration

## Implementation plan

1. Delete shared product/catalog code, HTTP, persistence, wiring, and its permission.
2. Rebaseline development migrations into one fresh PostgreSQL schema preserving finance and authorization constraints.
3. Require the reviewed document version on ledger revisions and transitions, using the existing NOWAIT lineage lock.
4. Prove arbitrary priced lines, overrides, credits, immutable history, concurrency, settlement, authentication, and fresh startup through domain/runtime tests.
5. Synchronize architecture and adopter docs; record consumer changes and verification in the PR.

This is the maintainer-authorized pre-production architecture change. No compatibility
adapters or data conversions are supplied. Fiona repositories are follow-on work and are
not changed here.

## Removed API and capabilities

| Surface | Removed |
|---|---|
| Domain package | All of `io.github.castab.commerce.offering`: `OfferingKey`, `OfferingCategoryKey`, `QuantityDimension`, `OfferingsCatalogId`, `OfferingPrice` and its price modes, `OfferingCategory`, `OfferingSelectionState`, `OfferingAvailability`, `Offering`, `OfferingCategorySelection`, `OfferingSelections`, `OfferingsViolation`, `StructuralOfferingsViolation`, `OfferingsPolicyResult`, `OfferingsEvaluation`, `OfferingsEvaluationResult`, `OfferingsEngine`, `OfferingsRevision`, `OfferingsSnapshotReference`, `OfferingsSnapshot`, and their factories/operations. |
| Runtime package | All of `io.github.castab.commerce.runtime.offering`: create/read/category and batch offering lifecycle operations, catalog result types, DTOs and `dto()` translators, `offeringsValidationFailed`, `OfferingsHttpAccess`, `OfferingsHttpBinding`, `offeringsHttpCapability`, and `offeringsOpenApiRenderer`. |
| Persistence/context | `OfferingsSnapshotRepository`, `PostgresOfferingsSnapshotRepository`, `RetiredCatalogValue`, internal `OfferingsCatalogJson` codecs, and `CommerceRuntimeContext.offeringsSnapshotRepository`. |
| Authorization | `CommercePermissions.OfferingsManage`, `commerce.offerings.manage`, its runtime definition, and the `commerce.offerings` permission group. The permission catalog mechanism remains. |
| HTTP/OpenAPI | The entire mountable offerings capability: current catalog and category/item reads; category add/update/retire/restore; batch offering add/update/retire/restore; retired offering/category discovery; all generated operation IDs and catalog/price schemas. Hosts can no longer mount those routes. |
| Database | All catalog tables, child tables, reservation/retirement state, indexes, checks, and historical catalog DDL. Former runtime V1-V13 scripts are replaced by one fresh V1 baseline. |

The runtime has no generic product or financial HTTP endpoint. No new HTTP endpoint,
pricing DSL, metadata bag, or pricing-authority framework replaces the removed subsystem.

## Preserved financial and authorization APIs

`LineItem(id, description, subDescription, quantity, price, taxAmount)` is the existing
self-contained financial value. A null quantity means a flat price; otherwise subtotal
is exact `price × quantity`. Tax is a supplied final amount for the whole line. Money
keeps exact BigDecimal scale, currency checks, and no implicit rounding or conversion.
Signed prices and tax support adjustments. A document requires nonempty ordered lines,
unique stable UUIDs, nonblank descriptions, and one currency; totals derive from the lines.

Initial snapshots remain stage-independent:

```kotlin
val estimate = FinancialDocument.Estimate.create(id, alreadyPricedLines)
val quote = FinancialDocument.Quote.create(id, alreadyPricedLines)
val invoice = FinancialDocument.Invoice.create(id, alreadyPricedLines)
context.financialLedger.create(estimate) // likewise quote or invoice
```

For atomic Fiona relationship writes, use `create(transaction, document)` inside the
application's `context.transactor.inTransaction` and write application records through
that same `Transaction`. These composition APIs do not authorize callers themselves.

| Remaining ledger operation | Contract |
|---|---|
| `create(document)` / `create(transaction, document)` | Store an application-authorized initial Estimate, Quote, or Invoice. |
| `changeOrder(id, changeOrder, expectedDocumentVersion)` | Append a same-stage revision; replace whole lines with stable IDs, add bespoke or signed lines, or remove lines. |
| `issueQuote(id, expectedDocumentVersion)` | Append the reviewed Estimate's Quote successor. |
| `issueInvoice(id, expectedDocumentVersion)` | Append the reviewed Quote's Invoice successor. |
| `get(reference)`, `latest(id)`, `history(id)` | Exact immutable document, current snapshot, and ascending complete history. |
| `version(reference)`, `latestVersion(id)`, `versionHistory(id)` | Documents plus their original database creation instants. |
| Payment/refund/deposit operations and reads | Unchanged recording, exact-version allocation, explicit refund unwinds, reconciliation, approvals/withdrawals, frozen amounts, and coherent lineage views. |

Every operation also has a caller-owned `Transaction` overload, with transaction first.
The three document mutation methods now require `expectedDocumentVersion: Version`.
Their former tokenless overloads are removed, without defaults or shims. Carry the version
the actor reviewed; do not reread latest merely to obtain a token before submitting an edit.
The existing NOWAIT lineage lock compares the token before reading its immutable source.
Stale or competing writes fail `CommerceFailure.Conflict`; missing lineages fail NotFound;
invalid stages fail IllegalTransition; invalid change orders fail ValidationFailed.
Lock/serialization failures require rolling back and retrying the whole caller transaction.
Repository predecessor/uniqueness checks and snapshot-insert triggers remain independent
guards. Payments still lock payment rows and retain their exact document allocation references.

USER/SERVICE principals, sessions, live role grants, service credential hashes and access
tokens, permission catalog, current-principal HTTP, and authorization administration remain.
`FinancialDocumentCreate` remains the conventional creation permission; there is no shared
financial revision/override permission. A consuming app must define and enforce its own
specific edit authority, rather than treating creation permission as implicit staff pricing
authority. No broad authorization redesign is introduced.

## Trust boundary and downstream work

Financial lines become authoritative after an authorized application operation commits
them. Shared domain/ledger APIs enforce financial invariants and lifecycle; they do not
infer trust from HTTP input. Applications own commercial authorization and provenance.

- `fionas-commerce`: remove imports of the deleted packages, catalog context wiring and
  route mounts, shared pricing/selection engine inheritance, removed DTO/OpenAPI reuse,
  offering permission grants, and any catalog foreign keys. Consume the newly released
  matching runtime/domain version. Use already-priced `LineItem` values at the ledger
  boundary and pass reviewed versions for staff changes and promotions. Keep inquiry
  associations, source/provenance, approved service plans, and workflow policy application-owned.
- `fionas-web` / its SvelteKit server: own the business catalog and pricing module;
  validate untrusted customer selections, availability, and pricing policy server-side
  before constructing authoritative lines. Design Fiona's inquiry boundary deliberately
  so required inputs, idempotency intent, price provenance, and inquiry/estimate atomicity
  remain coherent. The BFF's service token alone cannot justify arbitrary browser amounts.
- Staff edit flows: securely verify or derive the responsible staff session/principal and
  its edit/override permissions at the consuming application boundary. A submitted staff
  ID string is not evidence of authority. Carry the reviewed document version and handle
  conflicts by refreshing for review, not silently rebasing the edit.

These are follow-on changes, not implemented in this PR. There is no copied/shared-catalog
compatibility layer, frontend work, or modification of Fiona's deposit/booking/payment policy.

## Developer database reset and release

This is a fresh-database rebaseline, not an upgrade migration. Stop applications using
the old development database and deliberately recreate that entire throwaway database,
including runtime and application schemas and both Flyway histories. For a local database,
replace the placeholders with its actual name/user:

```sh
dropdb --host localhost --username <database_user> <developer_database>
createdb --host localhost --username <database_user> --owner <database_user> <developer_database>
```

These commands are instructions only; this PR does not reset any developer database.
A disposable Docker database can instead be recreated through its own development setup.
Do not reset production data, run Flyway repair, set baselineOnMigrate, or retain an old
application schema with its old history while pretending it matches this runtime.

Apply `MigrationLifecycle(dataSource, applicationMigrations).migrate()` or start once with
`migrations.onStartup = MIGRATE` (`MIGRATIONS_ON_STARTUP=MIGRATE`). Subsequent startup with
`VALIDATE` must succeed; runtime and application migration streams remain independently
owned, runtime first. Integration tests use newly created PostgreSQL databases and the
real migrations, not an alternate schema.

Release both modules together at a new breaking version, such as `v0.1.0`, after merge
and green CI. Never overwrite `0.0.22` or another published version. Version derivation
remains release-tag-driven; build files, credentials, coordinates, workflow triggers,
and publishing behavior are unchanged. Consumers must explicitly upgrade and reset
throwaway databases before using the new baseline.
