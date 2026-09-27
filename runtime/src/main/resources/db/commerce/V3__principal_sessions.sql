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
