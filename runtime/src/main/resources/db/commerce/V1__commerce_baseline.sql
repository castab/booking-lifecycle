-- Pre-production rebaseline: recreate developer databases from this schema.
-- Runtime-owned financial facts and authorization; application migrations remain separate.

-- Authenticated principal sessions, owned by commerce-runtime.
--
-- A session maps an opaque token to exactly one principal. Only the SHA-256 digest of the
-- token is stored; the raw token is returned to the application once, when the session is
-- created, and never persisted. Permissions are never stored here: a session establishes
-- identity, and authorization always asks the application's current permission resolver.
--
-- The principal is stored as a runtime-controlled discriminator plus the identifier's
-- UUID, for exactly the principal kinds commerce-runtime supports. Supporting another
-- PrincipalId kind is deliberate: the runtime adds its explicit mapping and a later
-- migration widens this check constraint. Nothing is ever stored by class name or toString.
--
-- Every timestamp is written from the runtime's clock; no default reads the database
-- clock, so creation, expiry, and revocation are judged against one time source.
CREATE TABLE commerce.principal_sessions (
    session_id     uuid PRIMARY KEY,
    principal_kind text NOT NULL CONSTRAINT principal_sessions_principal_kind CHECK (principal_kind IN ('USER', 'SERVICE')),
    principal_id   uuid NOT NULL,
    token_digest   bytea NOT NULL CONSTRAINT principal_sessions_token_digest_length CHECK (octet_length(token_digest) = 32),
    created_at     timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    CONSTRAINT principal_sessions_token_digest_unique UNIQUE (token_digest),
    CONSTRAINT principal_sessions_expiry CHECK (expires_at > created_at),
    CONSTRAINT principal_sessions_revocation CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

-- Revoking every session of one principal touches only sessions not yet revoked.
CREATE INDEX principal_sessions_principal ON commerce.principal_sessions (principal_kind, principal_id)
    WHERE revoked_at IS NULL;

-- Finding sessions past their expiry, for future cleanup.
CREATE INDEX principal_sessions_expires_at ON commerce.principal_sessions (expires_at);

-- Runtime-owned identity and RBAC state. Credentials and vertical profiles belong to applications.
CREATE TABLE commerce.principals (
    principal_kind text NOT NULL CHECK (principal_kind IN ('USER', 'SERVICE')),
    principal_id uuid NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    PRIMARY KEY (principal_kind, principal_id)
);

CREATE TABLE commerce.users (
    principal_kind text NOT NULL DEFAULT 'USER' CHECK (principal_kind = 'USER'),
    principal_id uuid NOT NULL UNIQUE,
    username text NOT NULL CHECK (btrim(username) ~ '^[A-Za-z0-9._-]+$'),
    normalized_username text NOT NULL UNIQUE CHECK (normalized_username = lower(btrim(username))),
    first_name text,
    last_name text,
    display_name text NOT NULL CHECK (length(btrim(display_name)) > 0),
    PRIMARY KEY (principal_kind, principal_id),
    FOREIGN KEY (principal_kind, principal_id) REFERENCES commerce.principals (principal_kind, principal_id)
);

CREATE TABLE commerce.service_identities (
    principal_kind text NOT NULL DEFAULT 'SERVICE' CHECK (principal_kind = 'SERVICE'),
    principal_id uuid NOT NULL UNIQUE,
    name text NOT NULL CHECK (length(btrim(name)) > 0),
    PRIMARY KEY (principal_kind, principal_id),
    FOREIGN KEY (principal_kind, principal_id) REFERENCES commerce.principals (principal_kind, principal_id)
);

CREATE TABLE commerce.roles (
    role_key text PRIMARY KEY CHECK (length(btrim(role_key)) > 0),
    display_name text NOT NULL CHECK (length(btrim(display_name)) > 0),
    description text
);

CREATE TABLE commerce.role_permissions (
    role_key text NOT NULL REFERENCES commerce.roles (role_key),
    permission_key text NOT NULL CHECK (length(btrim(permission_key)) > 0),
    PRIMARY KEY (role_key, permission_key)
);

CREATE TABLE commerce.principal_roles (
    principal_kind text NOT NULL,
    principal_id uuid NOT NULL,
    role_key text NOT NULL REFERENCES commerce.roles (role_key),
    PRIMARY KEY (principal_kind, principal_id, role_key),
    FOREIGN KEY (principal_kind, principal_id) REFERENCES commerce.principals (principal_kind, principal_id)
);

CREATE TABLE commerce.financial_document_snapshots (
    document_id uuid NOT NULL,
    version integer NOT NULL CHECK (version >= 1),
    previous_version integer,
    stage text NOT NULL CHECK (stage IN ('ESTIMATE', 'QUOTE', 'INVOICE')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lines jsonb NOT NULL CONSTRAINT financial_document_lines_array CHECK (jsonb_typeof(lines) = 'array'),
    PRIMARY KEY (document_id, version),
    UNIQUE (document_id, previous_version),
    CONSTRAINT financial_document_sequence CHECK (
        (version = 1 AND previous_version IS NULL) OR
        (version > 1 AND previous_version IS NOT NULL AND previous_version = version - 1)
    ),
    CONSTRAINT financial_document_predecessor FOREIGN KEY (document_id, previous_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version)
);

CREATE TABLE commerce.payment_records (
    payment_id uuid PRIMARY KEY,
    amount numeric NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL,
    method text NOT NULL CHECK (method IN ('CASH', 'CHECK', 'CARD', 'BANK_TRANSFER', 'DIGITAL_WALLET', 'OTHER')),
    received_at_seconds bigint NOT NULL,
    received_at_nanos integer NOT NULL CHECK (received_at_nanos BETWEEN 0 AND 999999999),
    external_provider text,
    external_reference text,
    CONSTRAINT payment_external_pair CHECK (
        (external_provider IS NULL AND external_reference IS NULL) OR
        (external_provider IS NOT NULL AND length(btrim(external_provider)) > 0 AND
         external_reference IS NOT NULL AND length(btrim(external_reference)) > 0)
    ),
    UNIQUE (external_provider, external_reference)
);

CREATE TABLE commerce.payment_allocations (
    allocation_id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES commerce.payment_records (payment_id),
    document_id uuid NOT NULL,
    document_version integer NOT NULL,
    amount numeric NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL,
    allocated_at_seconds bigint NOT NULL,
    allocated_at_nanos integer NOT NULL CHECK (allocated_at_nanos BETWEEN 0 AND 999999999),
    FOREIGN KEY (document_id, document_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version)
);

CREATE INDEX payment_allocations_payment ON commerce.payment_allocations (payment_id);
CREATE INDEX payment_allocations_lineage ON commerce.payment_allocations (document_id);

CREATE TABLE commerce.refund_records (
    refund_id uuid PRIMARY KEY,
    payment_id uuid NOT NULL REFERENCES commerce.payment_records (payment_id),
    amount numeric NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL,
    method text NOT NULL CHECK (method IN ('CASH', 'CHECK', 'CARD', 'BANK_TRANSFER', 'DIGITAL_WALLET', 'OTHER')),
    refunded_at_seconds bigint NOT NULL,
    refunded_at_nanos integer NOT NULL CHECK (refunded_at_nanos BETWEEN 0 AND 999999999),
    external_provider text,
    external_reference text,
    CONSTRAINT refund_external_pair CHECK (
        (external_provider IS NULL AND external_reference IS NULL) OR
        (external_provider IS NOT NULL AND length(btrim(external_provider)) > 0 AND
         external_reference IS NOT NULL AND length(btrim(external_reference)) > 0)
    ),
    UNIQUE (external_provider, external_reference)
);

CREATE TABLE commerce.refund_allocations (
    refund_allocation_id uuid PRIMARY KEY,
    refund_id uuid NOT NULL REFERENCES commerce.refund_records (refund_id),
    payment_allocation_id uuid NOT NULL REFERENCES commerce.payment_allocations (allocation_id),
    amount numeric NOT NULL CHECK (amount > 0),
    currency char(3) NOT NULL,
    allocated_at_seconds bigint NOT NULL,
    allocated_at_nanos integer NOT NULL CHECK (allocated_at_nanos BETWEEN 0 AND 999999999)
);

CREATE INDEX refund_records_payment ON commerce.refund_records (payment_id);
CREATE INDEX refund_allocations_refund ON commerce.refund_allocations (refund_id);
CREATE INDEX refund_allocations_payment_allocation ON commerce.refund_allocations (payment_allocation_id);

-- Long-lived credentials of SERVICE principals, owned by commerce-runtime.
--
-- A service may hold several credentials at once, so a consumer can move to a new
-- credential before the old one is revoked. Each credential has a stable, non-secret
-- identifier. Its secret is shown once, when it is created, and never stored: only an
-- Argon2id hash in PHC string format is kept. Revocation is recorded, never deleted, so the
-- credential's history stays visible to administrators.
--
-- Credentials belong to service identities only: the foreign key and the principal_kind
-- check make a USER credential unrepresentable. Nothing here records permissions or roles;
-- a credential proves identity, and authorization always asks the current role grants.
--
-- Every timestamp is written from the runtime's clock; no default reads the database clock.
-- Authentication finds a credential by its primary key; chr(36) is '$', spelled out so the
-- hash prefix cannot be read as a dollar-quote delimiter.
CREATE TABLE commerce.service_credentials (
    credential_id  uuid PRIMARY KEY,
    principal_kind text NOT NULL DEFAULT 'SERVICE' CONSTRAINT service_credentials_principal_kind CHECK (principal_kind = 'SERVICE'),
    principal_id   uuid NOT NULL,
    label          text NOT NULL CONSTRAINT service_credentials_label CHECK (length(btrim(label)) > 0 AND length(label) <= 200),
    secret_hash    text NOT NULL CONSTRAINT service_credentials_secret_hash CHECK (starts_with(secret_hash, chr(36) || 'argon2id' || chr(36))),
    created_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    CONSTRAINT service_credentials_service FOREIGN KEY (principal_kind, principal_id)
        REFERENCES commerce.service_identities (principal_kind, principal_id),
    CONSTRAINT service_credentials_revocation CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

-- Listing one service's credentials, newest first.
CREATE INDEX service_credentials_principal ON commerce.service_credentials (principal_id, created_at);

-- A lineage's current snapshot reference is also its stable mutation lock. Updating this
-- reference when a snapshot is appended makes a stale REPEATABLE_READ writer conflict,
-- which a lock on an immutable snapshot alone cannot do. No financial amounts live here.
CREATE TABLE commerce.financial_document_lineages (
    document_id uuid PRIMARY KEY,
    latest_version integer NOT NULL CHECK (latest_version >= 1),
    FOREIGN KEY (document_id, latest_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version)
);

-- Maintain the reference for every snapshot writer, including direct repository writers.
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
