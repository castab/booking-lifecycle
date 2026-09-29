-- Test-only: deliberately does not qualify its table. Application migrations run with the
-- application's own schema as their only search path entry, so this table must land in that
-- schema and never in public.
CREATE TABLE test_application_unqualified (
    id uuid PRIMARY KEY
);
