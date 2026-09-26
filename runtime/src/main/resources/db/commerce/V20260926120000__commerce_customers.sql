-- Commerce-owned tables live in the `commerce` schema, tracked by their own Flyway history
-- (commerce.flyway_schema_history). Applications keep their own tables and migrations
-- outside it.

-- The durable customer identity from commerce-domain: id, name, and email only. Phone
-- numbers are booking-scoped operational contact data and do not belong here.
CREATE TABLE commerce.customers (
    id    uuid PRIMARY KEY,
    name  text NOT NULL CHECK (btrim(name) <> ''),
    email text NOT NULL CHECK (btrim(email) <> '')
);
