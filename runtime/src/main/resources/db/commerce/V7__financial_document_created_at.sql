-- Existing snapshots predate this field; their historical creation instants cannot be
-- reconstructed. The migration instant is the earliest authoritative persisted value.
ALTER TABLE commerce.financial_document_snapshots
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT clock_timestamp();
