-- No durable pre-V7 financial snapshots are supported. Refuse to assign them a
-- migration-time value that falsely claims to be their creation instant.
-- Hold the table against concurrent inserts until the check and ALTER commit.
LOCK TABLE commerce.financial_document_snapshots IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM commerce.financial_document_snapshots) THEN
        RAISE EXCEPTION 'V7 cannot timestamp preexisting financial document snapshots; recreate the ephemeral database';
    END IF;
END
$$;

ALTER TABLE commerce.financial_document_snapshots
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT clock_timestamp();
