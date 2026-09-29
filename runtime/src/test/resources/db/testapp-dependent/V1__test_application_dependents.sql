-- Test-only application migration that references a runtime-owned table from the
-- db/testruntime stand-in, as an application with a foreign key into the commerce schema
-- would. It fails unless the runtime stream has already run.
CREATE TABLE testapp.test_application_dependents (
    runtime_record_id uuid NOT NULL REFERENCES commerce.test_runtime_records (id)
);
