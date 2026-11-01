-- Who can sign in, and their sessions. A session row holds only the SHA-256 of its token, so reading
-- this table never yields a usable credential. Roles are read on every request: a change applies at once.

CREATE TABLE identity.member (
    id               uuid        PRIMARY KEY,
    paypal_subject   text        NOT NULL UNIQUE,
    payer_id         text,
    email            text,
    display_name     text,
    verified_account boolean     NOT NULL DEFAULT false,
    status           text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE identity.staff (
    id           uuid        PRIMARY KEY,
    email        text        NOT NULL UNIQUE,
    display_name text        NOT NULL,
    roles        text[]      NOT NULL CHECK (roles <@ ARRAY['OFFICER', 'CHECKER', 'ADMIN']),
    member_id    uuid        REFERENCES identity.member (id),   -- set when a staff user is also a member
    status       text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE identity.session (
    token_hash   text        PRIMARY KEY,
    actor_type   text        NOT NULL CHECK (actor_type IN ('MEMBER', 'STAFF')),
    actor_id     uuid        NOT NULL,
    auth_time    timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz
);
CREATE INDEX session_by_actor ON identity.session (actor_type, actor_id);
