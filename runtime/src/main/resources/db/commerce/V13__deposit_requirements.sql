-- Additive: documents without a requirement have no requirement history.
-- Keep the initial references and installation of their maintenance triggers atomic
-- with respect to snapshot writers on a populated database.
LOCK TABLE commerce.financial_document_snapshots IN SHARE ROW EXCLUSIVE MODE;
-- A lineage's current snapshot reference is also its stable mutation lock. Updating this
-- reference when a snapshot is appended makes a stale REPEATABLE_READ writer conflict,
-- which a lock on an immutable snapshot alone cannot do. No financial amounts live here.
CREATE TABLE commerce.financial_document_lineages (
    document_id uuid PRIMARY KEY,
    latest_version integer NOT NULL CHECK (latest_version >= 1),
    FOREIGN KEY (document_id, latest_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version)
);

INSERT INTO commerce.financial_document_lineages (document_id, latest_version)
    SELECT document_id, max(version) FROM commerce.financial_document_snapshots GROUP BY document_id;

-- Maintain the reference for every snapshot writer, including older runtime instances.
-- Lock before the snapshot insert. Never wait for a lineage: a caller may already
-- own a payment for which the lineage holder is waiting. NOWAIT breaks that cycle,
-- including for writers that do not first call the runtime's lockLineage method.
CREATE FUNCTION commerce.lock_financial_document_lineage() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    current_version integer;
BEGIN
    IF NEW.version > 1 THEN
        SELECT latest_version INTO current_version FROM commerce.financial_document_lineages
            WHERE document_id = NEW.document_id FOR NO KEY UPDATE NOWAIT;
        IF current_version IS DISTINCT FROM NEW.previous_version THEN
            RAISE EXCEPTION 'Financial document has a stale predecessor' USING ERRCODE = '23505';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION commerce.advance_financial_document_lineage() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.version = 1 THEN
        INSERT INTO commerce.financial_document_lineages (document_id, latest_version)
            VALUES (NEW.document_id, NEW.version);
    ELSE
        UPDATE commerce.financial_document_lineages SET latest_version = NEW.version
            WHERE document_id = NEW.document_id;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER financial_document_lineage_lock
    BEFORE INSERT ON commerce.financial_document_snapshots
    FOR EACH ROW EXECUTE FUNCTION commerce.lock_financial_document_lineage();
CREATE TRIGGER financial_document_lineage_advance
    AFTER INSERT ON commerce.financial_document_snapshots
    FOR EACH ROW EXECUTE FUNCTION commerce.advance_financial_document_lineage();

CREATE TABLE commerce.deposit_requirement_revisions (
    document_id uuid NOT NULL REFERENCES commerce.financial_document_lineages (document_id),
    revision integer NOT NULL CHECK (revision >= 1),
    previous_revision integer,
    kind text NOT NULL CHECK (kind IN ('ACTIVE', 'WITHDRAWN')),
    -- A withdrawal's predecessor must be Active. Active successors may follow either kind.
    withdrawn_predecessor_kind text GENERATED ALWAYS AS
        (CASE WHEN kind = 'WITHDRAWN' THEN 'ACTIVE' END) STORED,
    approval_version integer,
    terms_kind text,
    terms_amount numeric,
    terms_scale integer,
    required_amount numeric,
    required_scale integer,
    currency text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (document_id, revision),
    UNIQUE (document_id, previous_revision),
    UNIQUE (document_id, revision, kind),
    CONSTRAINT deposit_requirement_sequence CHECK (
        (revision = 1 AND previous_revision IS NULL) OR
        (revision > 1 AND previous_revision IS NOT NULL AND previous_revision = revision - 1)
    ),
    FOREIGN KEY (document_id, previous_revision)
        REFERENCES commerce.deposit_requirement_revisions (document_id, revision),
    FOREIGN KEY (document_id, previous_revision, withdrawn_predecessor_kind)
        REFERENCES commerce.deposit_requirement_revisions (document_id, revision, kind),
    FOREIGN KEY (document_id, approval_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version),
    CONSTRAINT deposit_requirement_representation CHECK (
        (kind = 'ACTIVE' AND approval_version IS NOT NULL AND approval_version >= 1
         AND terms_kind IS NOT NULL AND terms_kind IN ('FIXED', 'PERCENTAGE')
         AND terms_amount IS NOT NULL AND terms_amount > 0 AND terms_amount < 'Infinity'::numeric
         AND terms_scale IS NOT NULL
         AND (terms_kind = 'FIXED' OR terms_amount <= 100)
         AND required_amount IS NOT NULL AND required_amount > 0 AND required_amount < 'Infinity'::numeric
         AND required_scale IS NOT NULL
         AND currency IS NOT NULL AND currency ~ '^[A-Z]{3}$') OR
        (kind = 'WITHDRAWN' AND revision > 1 AND approval_version IS NULL
         AND terms_kind IS NULL AND terms_amount IS NULL AND terms_scale IS NULL
         AND required_amount IS NULL AND required_scale IS NULL AND currency IS NULL)
    )
);

-- This stream is append-only even when accessed directly through a caller's handle.
CREATE FUNCTION commerce.reject_deposit_requirement_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Deposit requirement revisions are immutable';
END
$$;

CREATE TRIGGER deposit_requirement_immutable
    BEFORE UPDATE OR DELETE ON commerce.deposit_requirement_revisions
    FOR EACH ROW EXECUTE FUNCTION commerce.reject_deposit_requirement_mutation();
