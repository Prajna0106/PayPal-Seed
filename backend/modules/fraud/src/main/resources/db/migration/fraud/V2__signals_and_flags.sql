-- Fraud signals and flags (ADR 0019). Facts are what happened; signals are rules that fired on facts; a flag is the
-- advice an officer reviews. Nothing here blocks a member. Postgres only: no search engine is needed at this size.

-- The secret salt for hashing stable identifiers (a PayPal payer id, a payout recipient). One row, never exposed.
-- A hash cannot be reversed or joined to another system; two facts with the same value get the same hash.
CREATE TABLE fraud.hash_salt (
    id         smallint    PRIMARY KEY CHECK (id = 1),
    salt       text        NOT NULL CHECK (length(salt) >= 32),
    created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO fraud.hash_salt (id, salt)
VALUES (1, replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', ''));

-- What happened, append-only. source_key makes an observation idempotent (an event id).
CREATE TABLE fraud.fact (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id    uuid        NOT NULL,
    kind         text        NOT NULL CHECK (kind IN ('ACCOUNT_CREATED', 'GOAL_CREATED', 'DEPOSIT', 'WITHDRAWAL',
                     'APPLICATION_SUBMITTED', 'LOAN_DISBURSED', 'PAYMENT_FAILED', 'PAYER_SEEN', 'PAYOUT_RECIPIENT_CHANGED')),
    occurred_at  timestamptz NOT NULL,
    source_key   text        NOT NULL UNIQUE,
    amount_cents bigint      CHECK (amount_cents >= 0),
    ref_hash     text        CHECK (ref_hash ~ '^[0-9a-f]{64}$'),   -- HMAC-SHA256 of the identifier; never the identifier
    attributes   jsonb       NOT NULL DEFAULT '{}'::jsonb,           -- allow-listed keys only (goalId, loanId)
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX fact_by_member ON fraud.fact (member_id, occurred_at, id);
CREATE INDEX fact_by_ref ON fraud.fact (ref_hash, member_id) WHERE ref_hash IS NOT NULL;

-- A flag is advice for people. One open flag per member; a reviewed flag is final and new evidence opens a new one.
CREATE TABLE fraud.flag (
    id          uuid        PRIMARY KEY,
    member_id   uuid        NOT NULL,
    status      text        NOT NULL CHECK (status IN ('OPEN', 'REVIEWED_CLEAR', 'CONFIRMED')),
    score       int         NOT NULL CHECK (score >= 0),
    raised_at   timestamptz NOT NULL,
    reviewed_at timestamptz,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT flag_reviewed_state CHECK ((status = 'OPEN') = (reviewed_at IS NULL))
);
CREATE UNIQUE INDEX flag_one_open_per_member ON fraud.flag (member_id) WHERE status = 'OPEN';
CREATE INDEX flag_by_member ON fraud.flag (member_id, id DESC);
CREATE INDEX flag_queue ON fraud.flag (status, id DESC);

-- A rule that fired. fingerprint is a hash of the rule and the facts it used, so re-running a rule on the same facts
-- adds nothing, and the same evidence can never raise a second flag after an officer cleared the first.
CREATE TABLE fraud.signal (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id   uuid        NOT NULL,
    flag_id     uuid        REFERENCES fraud.flag (id),   -- null until the member's score reaches the threshold
    code        text        NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    weight      int         NOT NULL CHECK (weight > 0),
    fingerprint text        NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    explanation text        NOT NULL,
    evidence    jsonb       NOT NULL,                      -- counts, windows and fact ids: numbers and ids only
    detected_at timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (member_id, code, fingerprint)
);
CREATE INDEX signal_by_flag ON fraud.signal (flag_id) WHERE flag_id IS NOT NULL;
CREATE INDEX signal_pending ON fraud.signal (member_id) WHERE flag_id IS NULL;

-- One review per flag, written by an officer. The note stays here; the audit log gets the decision only.
CREATE TABLE fraud.review (
    id              uuid        PRIMARY KEY,
    flag_id         uuid        NOT NULL UNIQUE REFERENCES fraud.flag (id),
    decision        text        NOT NULL CHECK (decision IN ('CLEAR', 'CONFIRM')),
    note            text        CHECK (length(note) <= 1000),
    staff_id        uuid        NOT NULL,
    idempotency_key text        NOT NULL UNIQUE,
    request_hash    text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT review_confirm_has_note CHECK (decision <> 'CONFIRM' OR coalesce(length(btrim(note)), 0) > 0)
);

-- Append-only facts, enforced by the database rather than by convention.
CREATE FUNCTION fraud.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END $$;

-- A signal may be attached to a flag once; nothing else about it changes.
CREATE FUNCTION fraud.signal_attach_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'fraud.signal is append-only: DELETE is not allowed' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.flag_id IS NOT NULL
       OR (NEW.id, NEW.member_id, NEW.code, NEW.weight, NEW.fingerprint, NEW.explanation, NEW.evidence, NEW.detected_at)
          IS DISTINCT FROM (OLD.id, OLD.member_id, OLD.code, OLD.weight, OLD.fingerprint, OLD.explanation, OLD.evidence, OLD.detected_at) THEN
        RAISE EXCEPTION 'fraud.signal is append-only: only an unattached signal can be attached to a flag'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER fact_append_only BEFORE UPDATE OR DELETE ON fraud.fact
    FOR EACH ROW EXECUTE FUNCTION fraud.reject_mutation();
CREATE TRIGGER fact_no_truncate BEFORE TRUNCATE ON fraud.fact
    FOR EACH STATEMENT EXECUTE FUNCTION fraud.reject_mutation();
CREATE TRIGGER signal_attach_only BEFORE UPDATE OR DELETE ON fraud.signal
    FOR EACH ROW EXECUTE FUNCTION fraud.signal_attach_only();
CREATE TRIGGER review_append_only BEFORE UPDATE OR DELETE ON fraud.review
    FOR EACH ROW EXECUTE FUNCTION fraud.reject_mutation();
CREATE TRIGGER flag_no_delete BEFORE DELETE ON fraud.flag
    FOR EACH ROW EXECUTE FUNCTION fraud.reject_mutation();
CREATE TRIGGER hash_salt_fixed BEFORE UPDATE OR DELETE ON fraud.hash_salt
    FOR EACH ROW EXECUTE FUNCTION fraud.reject_mutation();
