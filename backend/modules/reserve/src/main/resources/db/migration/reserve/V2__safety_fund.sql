-- Community safety fund (ADR 0019). The ledger holds the money; these tables hold the fund's own facts:
-- who gave (only what the donor chose to share), which loans it covered, and the counters the public
-- summary reads in one row. Money is bigint cents.

-- One row. Updated in the transaction that posts the matching ledger entry, under a row lock, so the
-- balance can never be negative and two covers can never spend the same cent.
CREATE TABLE reserve.fund (
    id                   smallint    PRIMARY KEY CHECK (id = 1),
    balance_cents        bigint      NOT NULL CHECK (balance_cents >= 0),
    total_donated_cents  bigint      NOT NULL CHECK (total_donated_cents >= 0),
    total_paid_out_cents bigint      NOT NULL CHECK (total_paid_out_cents >= 0),
    donation_count       int         NOT NULL CHECK (donation_count >= 0),
    members_covered      int         NOT NULL CHECK (members_covered >= 0),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fund_balance_is_donations_minus_payouts CHECK (balance_cents = total_donated_cents - total_paid_out_cents)
);
INSERT INTO reserve.fund VALUES (1, 0, 0, 0, 0, 0, now());

-- What a donor asked for before going to PayPal. The custom id on the PayPal order points here.
CREATE TABLE reserve.donation_intent (
    id              uuid        PRIMARY KEY,
    idempotency_key text        NOT NULL UNIQUE,
    request_hash    text        NOT NULL,
    anonymous       boolean     NOT NULL,
    display_name    text        CHECK (length(display_name) BETWEEN 1 AND 40),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT intent_name_only_when_public CHECK (anonymous = (display_name IS NULL))
);

-- One row per PayPal capture. Append-only. No email, no payer id: only a name the donor chose to show.
CREATE TABLE reserve.donation (
    id               uuid        PRIMARY KEY,
    capture_id       text        NOT NULL UNIQUE,   -- PayPal capture id: recording the same gift twice is impossible
    amount_cents     bigint      NOT NULL CHECK (amount_cents > 0),
    fee_cents        bigint      NOT NULL CHECK (fee_cents >= 0 AND fee_cents < amount_cents),
    anonymous        boolean     NOT NULL,
    display_name     text        CHECK (length(display_name) BETWEEN 1 AND 40),
    journal_entry_id uuid        NOT NULL,
    business_date    date        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT donation_name_only_when_public CHECK (anonymous = (display_name IS NULL))
);
CREATE INDEX donation_recent_public ON reserve.donation (created_at DESC, id DESC) WHERE NOT anonymous;

-- A bridge for one member's loan, raised by an officer and decided by maker and checker (governance).
CREATE TABLE reserve.cover_request (
    id                  uuid        PRIMARY KEY,   -- also the governance subject
    member_id           uuid        NOT NULL,
    loan_id             uuid        NOT NULL,
    amount_cents        bigint      NOT NULL CHECK (amount_cents > 0),
    reason_code         text        NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    note                text        CHECK (length(note) <= 500),
    hardship_case_id    uuid,
    status              text        NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'FAILED')),
    approval_request_id uuid,
    requested_by        uuid        NOT NULL,
    requested_on        date        NOT NULL,
    idempotency_key     text        NOT NULL UNIQUE,
    request_hash        text        NOT NULL,
    journal_entry_id    uuid,
    closed_reason       text,
    closed_at           timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT cover_closed_state CHECK ((status = 'PENDING_APPROVAL') = (closed_at IS NULL)),
    CONSTRAINT cover_paid_only_when_approved CHECK ((status = 'APPROVED') = (journal_entry_id IS NOT NULL))
);
-- One open request per loan, whatever path tries to open a second.
CREATE UNIQUE INDEX cover_one_open_per_loan ON reserve.cover_request (loan_id) WHERE status = 'PENDING_APPROVAL';
CREATE UNIQUE INDEX cover_by_approval ON reserve.cover_request (approval_request_id) WHERE approval_request_id IS NOT NULL;
CREATE INDEX cover_by_member_year ON reserve.cover_request (member_id, requested_on) INCLUDE (amount_cents, status);
CREATE INDEX cover_queue ON reserve.cover_request (status, id DESC);

-- Members the fund has helped; keeps "members covered" a counter and not a scan.
CREATE TABLE reserve.covered_member (
    member_id        uuid        PRIMARY KEY,
    first_covered_on date        NOT NULL
);

-- Outstanding balance per loan, fed by lending events. Out-of-order or repeated delivery is harmless:
-- an older event never overwrites a newer one (event ids are time-ordered UUIDs).
CREATE TABLE reserve.loan_exposure (
    loan_id           uuid        PRIMARY KEY,
    outstanding_cents bigint      NOT NULL CHECK (outstanding_cents >= 0),
    last_event_id     uuid        NOT NULL,
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX loan_exposure_open ON reserve.loan_exposure (outstanding_cents) WHERE outstanding_cents > 0;

-- Append-only facts, enforced by the database rather than by convention.
CREATE FUNCTION reserve.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END $$;

CREATE TRIGGER donation_append_only BEFORE UPDATE OR DELETE ON reserve.donation
    FOR EACH ROW EXECUTE FUNCTION reserve.reject_mutation();
CREATE TRIGGER donation_intent_append_only BEFORE UPDATE OR DELETE ON reserve.donation_intent
    FOR EACH ROW EXECUTE FUNCTION reserve.reject_mutation();
CREATE TRIGGER donation_no_truncate BEFORE TRUNCATE ON reserve.donation
    FOR EACH STATEMENT EXECUTE FUNCTION reserve.reject_mutation();
CREATE TRIGGER cover_request_no_delete BEFORE DELETE ON reserve.cover_request
    FOR EACH ROW EXECUTE FUNCTION reserve.reject_mutation();
