-- Test-only stand-in for a runtime migration: a table in the commerce schema.
-- Specs use this stream to prove that application migrations may depend on runtime-owned
-- objects created earlier in the same run.
CREATE TABLE commerce.test_runtime_records (
    id uuid PRIMARY KEY
);
