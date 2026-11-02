-- Members, staff and the consent ledger (ADR 0012).

-- One row per person who signed in with PayPal. id is the member id every other module already uses.
-- email and display_name are personal data: they are never copied into events, logs or model prompts.
CREATE TABLE identity.member (
    id                uuid        PRIMARY KEY,
    paypal_payer_id   text        NOT NULL,
    email             text,
    display_name      text,
    language          text        NOT NULL DEFAULT 'en',
    wallet_preference text        NOT NULL DEFAULT 'PAYPAL',
    paypal_verified   boolean     NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL,
    last_login_at     timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT member_payer_unique UNIQUE (paypal_payer_id),
    CONSTRAINT member_language_valid CHECK (language IN ('en', 'es')),
    CONSTRAINT member_wallet_valid CHECK (wallet_preference IN ('PAYPAL', 'VENMO'))
);

CREATE TABLE identity.staff (
    id           uuid        PRIMARY KEY,
    display_name text        NOT NULL,
    email        text,
    roles        text[]      NOT NULL,
    active       boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT staff_roles_valid CHECK (cardinality(roles) > 0 AND roles <@ ARRAY['OFFICER', 'CHECKER', 'ADMIN'])
);

-- What each member agreed to. An entry is never edited or deleted; the only change the database accepts is
-- closing an open entry by setting revoked_at once. Granting again appends a new row.
CREATE TABLE identity.consent (
    id         uuid        PRIMARY KEY,
    member_id  uuid        NOT NULL REFERENCES identity.member (id),
    type       text        NOT NULL,
    version    text        NOT NULL,
    granted_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT consent_type_valid CHECK (type IN ('TERMS', 'PRIVACY', 'INCOME_READ', 'RECORD_SHARING')),
    CONSTRAINT consent_revoked_after_granted CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);

-- At most one open entry per member and type.
CREATE UNIQUE INDEX consent_one_active ON identity.consent (member_id, type) WHERE revoked_at IS NULL;
CREATE INDEX consent_member ON identity.consent (member_id, granted_at DESC);

CREATE FUNCTION identity.guard_consent_ledger() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.revoked_at IS NULL
       AND NEW.revoked_at IS NOT NULL
       AND NEW.id = OLD.id
       AND NEW.member_id = OLD.member_id
       AND NEW.type = OLD.type
       AND NEW.version = OLD.version
       AND NEW.granted_at = OLD.granted_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION '%.% is an append-only ledger: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END $$;

CREATE TRIGGER consent_ledger_guard BEFORE UPDATE OR DELETE ON identity.consent
    FOR EACH ROW EXECUTE FUNCTION identity.guard_consent_ledger();

CREATE FUNCTION identity.reject_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%.% is an append-only ledger: TRUNCATE is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END $$;

CREATE TRIGGER consent_no_truncate BEFORE TRUNCATE ON identity.consent
    FOR EACH STATEMENT EXECUTE FUNCTION identity.reject_truncate();
