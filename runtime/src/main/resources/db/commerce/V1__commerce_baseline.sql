-- The baseline of the runtime migration stream. Flyway creates the `commerce` schema, which
-- commerce-runtime owns together with every object in it; this stream is tracked in
-- commerce.flyway_schema_history, independently of any application's migrations.
--
-- Runtime migrations create, alter, or drop runtime-owned objects in the `commerce` schema
-- only, and never touch application-owned objects. The runtime owns no table yet. Runtime
-- tables that applications may reference become part of the published database contract
-- and evolve by expand -> migrate -> contract.
COMMENT ON SCHEMA commerce IS 'Owned by commerce-runtime. Application-owned objects never belong here.';
