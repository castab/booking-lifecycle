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
