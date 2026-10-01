-- No pre-V8 offering data is supported: never invent historical selection semantics.
-- Released migrations remain unchanged; recreate an ephemeral populated database.
LOCK TABLE commerce.offerings IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM commerce.offerings) THEN
        RAISE EXCEPTION 'V8 cannot assign selection and availability to preexisting offerings; recreate the ephemeral database';
    END IF;
END
$$;

ALTER TABLE commerce.offerings
    ADD COLUMN selection_state text NOT NULL,
    ADD COLUMN availability text NOT NULL,
    ADD CONSTRAINT offering_selection_state CHECK (selection_state IN ('ENABLED', 'DISABLED')),
    ADD CONSTRAINT offering_availability CHECK (availability IN ('AVAILABLE', 'UNAVAILABLE')),
    ADD CONSTRAINT offering_selection_availability CHECK (selection_state <> 'DISABLED' OR availability = 'AVAILABLE');
