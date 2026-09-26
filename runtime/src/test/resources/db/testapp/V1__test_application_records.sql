-- An application-owned table, migrated from an application migration location after the
-- commerce migrations. It depends on no commerce table: relationships between application
-- entities and commerce facts are the application's own design.
CREATE TABLE public.test_application_records (
    id    uuid PRIMARY KEY,
    value text NOT NULL
);
