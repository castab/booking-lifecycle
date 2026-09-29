-- Test-only: an application migration that fails, because it references a commerce object
-- that does not exist. It proves that a failed application migration prevents startup.
CREATE TABLE testapp.test_application_broken (
    customer_id uuid NOT NULL REFERENCES commerce.no_such_table (id)
);
