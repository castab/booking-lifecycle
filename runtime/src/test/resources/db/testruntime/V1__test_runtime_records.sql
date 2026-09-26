-- Test-only stand-in for a runtime migration: a runtime-owned table in the commerce schema.
-- commerce-runtime currently owns no table an application could reference, so the migration
-- specs use this stream to prove that application migrations may depend on runtime-owned
-- objects created earlier in the same run.
CREATE TABLE commerce.test_runtime_records (
    id uuid PRIMARY KEY
);
