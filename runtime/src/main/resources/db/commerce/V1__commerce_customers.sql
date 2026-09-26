-- A runtime migration: it creates, alters, or drops runtime-owned objects in the `commerce`
-- schema only, and never touches application-owned objects. The runtime stream is tracked in
-- commerce.flyway_schema_history, independently of any application's migrations.
--
-- Applications may reference commerce.customers (id) from their own tables, so it is part
-- of the runtime's published database contract: evolve it by expand -> migrate -> contract.

-- The durable customer identity from commerce-domain: id, name, and email only. Phone
-- numbers are booking-scoped operational contact data and do not belong here.
CREATE TABLE commerce.customers (
    id    uuid PRIMARY KEY,
    name  text NOT NULL CHECK (btrim(name) <> ''),
    email text NOT NULL CHECK (btrim(email) <> '')
);
