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
