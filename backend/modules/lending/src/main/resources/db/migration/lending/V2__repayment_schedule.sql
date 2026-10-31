-- F4: repayment schedule, money events, allocation, aging and close of business.
-- Design: 02-design.md §4, ADR 0004 (immutable versions, derived status), ADR 0007 (durable timers).
-- Money is bigint cents (USD only). Dates are business dates in America/Los_Angeles.

-- ---------------------------------------------------------------------------------------------
-- Loan
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan (
    id                uuid        PRIMARY KEY,
    member_id         uuid        NOT NULL,
    currency          char(3)     NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
    principal_cents   bigint      NOT NULL CHECK (principal_cents > 0 AND principal_cents % 100 = 0),
    instalment_count  smallint    NOT NULL CHECK (instalment_count BETWEEN 1 AND 36),
    due_day           smallint    NOT NULL CHECK (due_day BETWEEN 1 AND 31),
    disbursed_on      date        NOT NULL,
    disbursement_ref  text        NOT NULL UNIQUE,  -- payout item id: registering a disbursement twice is a no-op
    policy_version    text        NOT NULL,
    status            text        NOT NULL CHECK (status IN ('ACTIVE', 'REPAID', 'COVERED', 'CANCELLED')),
    collection_mode   text        NOT NULL DEFAULT 'MANUAL' CHECK (collection_mode IN ('AUTOPAY', 'MANUAL')),
    current_version   int         NOT NULL DEFAULT 1 CHECK (current_version >= 1),
    -- derived caches, rewritten by every recalculation; the desk integrity check compares them with the ledger
    outstanding_cents bigint      NOT NULL CHECK (outstanding_cents >= 0),
    overpaid_cents    bigint      NOT NULL DEFAULT 0 CHECK (overpaid_cents >= 0),
    dpd               int         NOT NULL DEFAULT 0 CHECK (dpd >= 0),
    bucket            text        NOT NULL DEFAULT 'CURRENT'
                      CHECK (bucket IN ('CURRENT', 'DPD_1_7', 'DPD_8_30', 'DPD_31_60', 'DPD_60_PLUS')),
    bucket_since      date        NOT NULL,
    last_closed_on    date,        -- last business date whose close of business was applied (Fineract's last_closed_business_date)
    closed_on         date,
    shard             smallint    NOT NULL CHECK (shard BETWEEN 0 AND 63),  -- virtual shard for close of business
    row_version       bigint      NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT loan_closed_state CHECK ((status = 'ACTIVE') = (closed_on IS NULL)),
    CONSTRAINT loan_repaid_means_nothing_owed CHECK (status <> 'REPAID' OR outstanding_cents = 0)
);
CREATE INDEX loan_by_member ON lending.loan (member_id);
CREATE INDEX loan_active_by_bucket ON lending.loan (bucket, dpd) WHERE status = 'ACTIVE';
CREATE INDEX loan_active_by_shard ON lending.loan (shard, id) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------------------------
-- Immutable schedule versions (ADR 0004)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.schedule_version (
    loan_id         uuid        NOT NULL REFERENCES lending.loan (id),
    version         int         NOT NULL CHECK (version >= 1),
    reason          text        NOT NULL CHECK (reason IN
                        ('ORIGINAL', 'HARDSHIP_MOVE_DATE', 'HARDSHIP_PAUSE', 'HARDSHIP_REAMORTIZE', 'CORRECTION')),
    principal_cents bigint      NOT NULL CHECK (principal_cents > 0),
    instalment_count smallint   NOT NULL CHECK (instalment_count >= 1),
    policy_version  text        NOT NULL,
    caused_by       text        NOT NULL,  -- 'disbursement:<ref>' | 'approval:<id>' | 'staff:<id>'
    created_on      date        NOT NULL,  -- business date
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (loan_id, version),
    CONSTRAINT schedule_version_follows_previous CHECK (version = 1 OR reason <> 'ORIGINAL')
);

CREATE TABLE lending.instalment (
    loan_id      uuid     NOT NULL,
    version      int      NOT NULL,
    seq          smallint NOT NULL CHECK (seq >= 1),
    due_date     date     NOT NULL,
    amount_cents bigint   NOT NULL CHECK (amount_cents > 0 AND amount_cents % 100 = 0),
    PRIMARY KEY (loan_id, version, seq),
    FOREIGN KEY (loan_id, version) REFERENCES lending.schedule_version (loan_id, version)
);

-- INV-1: each version's instalments are numbered 1..n, never go back in time, and sum to the principal.
CREATE FUNCTION lending.assert_version_consistent() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_principal bigint;
    v_count     int;
    v_sum       bigint;
    v_rows      int;
    v_max_seq   int;
    v_backwards int;
BEGIN
    SELECT principal_cents, instalment_count INTO v_principal, v_count
      FROM lending.schedule_version WHERE loan_id = NEW.loan_id AND version = NEW.version;
    SELECT COALESCE(SUM(amount_cents), 0), COUNT(*), COALESCE(MAX(seq), 0)
      INTO v_sum, v_rows, v_max_seq
      FROM lending.instalment WHERE loan_id = NEW.loan_id AND version = NEW.version;
    SELECT COUNT(*) INTO v_backwards
      FROM lending.instalment a JOIN lending.instalment b
        ON a.loan_id = b.loan_id AND a.version = b.version AND b.seq = a.seq + 1
     WHERE a.loan_id = NEW.loan_id AND a.version = NEW.version AND b.due_date < a.due_date;
    IF v_sum <> v_principal OR v_rows <> v_count OR v_max_seq <> v_count OR v_backwards > 0 THEN
        RAISE EXCEPTION 'schedule % v% is inconsistent (sum % vs principal %, rows %, count %, backwards %)',
            NEW.loan_id, NEW.version, v_sum, v_principal, v_rows, v_count, v_backwards
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER schedule_version_consistent
    AFTER INSERT ON lending.schedule_version DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION lending.assert_version_consistent();
CREATE CONSTRAINT TRIGGER instalment_version_consistent
    AFTER INSERT ON lending.instalment DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION lending.assert_version_consistent();

-- ---------------------------------------------------------------------------------------------
-- Money events (Fineract's m_loan_transaction): one append-only table, folded into allocation
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.loan_transaction (
    id               uuid        PRIMARY KEY,   -- UUIDv7: (effective_date, id) is the fold order
    loan_id          uuid        NOT NULL REFERENCES lending.loan (id),
    type             text        NOT NULL CHECK (type IN ('RECEIPT', 'REVERSAL', 'OVERPAYMENT_REFUND')),
    source           text        NOT NULL CHECK (source IN
                         ('AUTOPAY', 'AUTOPAY_RETRY', 'CHECKOUT', 'PAYMENT_LINK', 'RECONCILER', 'PAYPAL', 'SEED')),
    paypal_txn_id    text        NOT NULL UNIQUE,  -- money identity: one PayPal transaction, one row (INV-3)
    reverses_id      uuid        REFERENCES lending.loan_transaction (id),
    amount_cents     bigint      NOT NULL CHECK (amount_cents > 0),
    fee_cents        bigint      NOT NULL DEFAULT 0 CHECK (fee_cents >= 0 AND fee_cents <= amount_cents),
    payer_ref        text,                         -- hashed PayPal payer id (fraud: payer mismatch); never raw
    occurred_at      timestamptz NOT NULL,         -- PayPal time (UTC)
    effective_date   date        NOT NULL,         -- occurred_at in the business time zone
    journal_entry_id uuid        NOT NULL,
    caused_by        text        NOT NULL,         -- webhook event id, command id or job run id
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reversal_has_target CHECK ((type = 'REVERSAL') = (reverses_id IS NOT NULL)),
    CONSTRAINT fee_only_on_receipts CHECK (type = 'RECEIPT' OR fee_cents = 0)
);
CREATE INDEX loan_transaction_fold_order ON lending.loan_transaction (loan_id, effective_date, id);

-- PayPal money that is pending (e.g. eCheck): not credited yet, but it holds off nudges and retries.
CREATE TABLE lending.pending_payment (
    paypal_txn_id text        PRIMARY KEY,
    loan_id       uuid        NOT NULL REFERENCES lending.loan (id),
    amount_cents  bigint      NOT NULL CHECK (amount_cents > 0),
    since         timestamptz NOT NULL
);
CREATE INDEX pending_payment_by_loan ON lending.pending_payment (loan_id);

-- Projection: paid amount per instalment of the current version. Rebuilt by every recalculation.
CREATE TABLE lending.instalment_allocation (
    loan_id               uuid     NOT NULL,
    version               int      NOT NULL,
    seq                   smallint NOT NULL,
    paid_cents            bigint   NOT NULL CHECK (paid_cents >= 0),
    paid_in_advance_cents bigint   NOT NULL CHECK (paid_in_advance_cents BETWEEN 0 AND paid_cents),
    fully_paid_on         date,
    PRIMARY KEY (loan_id, version, seq),
    FOREIGN KEY (loan_id, version, seq) REFERENCES lending.instalment (loan_id, version, seq)
);

-- Projection: how each transaction was spread over instalments (seq 0 = held as overpayment).
CREATE TABLE lending.transaction_allocation (
    transaction_id uuid     NOT NULL REFERENCES lending.loan_transaction (id),
    version        int      NOT NULL,
    seq            smallint NOT NULL CHECK (seq >= 0),
    cents          bigint   NOT NULL CHECK (cents <> 0),
    PRIMARY KEY (transaction_id, version, seq)
);

-- ---------------------------------------------------------------------------------------------
-- Delinquency history: append-only facts
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.instalment_missed (       -- INV-9: never retracted, even by a later reschedule
    loan_id   uuid     NOT NULL REFERENCES lending.loan (id),
    version   int      NOT NULL,
    seq       smallint NOT NULL,
    due_date  date     NOT NULL,
    missed_on date     NOT NULL,
    PRIMARY KEY (loan_id, version, seq)
);

CREATE TABLE lending.bucket_transition (       -- roll rates, cure rates and the audit trail
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    loan_id     uuid NOT NULL REFERENCES lending.loan (id),
    as_of       date NOT NULL,
    from_bucket text NOT NULL,
    to_bucket   text NOT NULL CHECK (to_bucket <> from_bucket),
    dpd         int  NOT NULL,
    cause       text NOT NULL CHECK (cause IN ('DISBURSEMENT', 'COB', 'PAYMENT', 'REVERSAL', 'RESCHEDULE')),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX bucket_transition_by_loan ON lending.bucket_transition (loan_id, as_of);
CREATE INDEX bucket_transition_by_date ON lending.bucket_transition (as_of);

-- ---------------------------------------------------------------------------------------------
-- Collection attempts and durable timers (ADR 0007)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE lending.collection_attempt (
    id          uuid        PRIMARY KEY,
    loan_id     uuid        NOT NULL REFERENCES lending.loan (id),
    version     int         NOT NULL,
    seq         smallint    NOT NULL,
    attempt_no  smallint    NOT NULL CHECK (attempt_no >= 1),
    kind        text        NOT NULL CHECK (kind IN ('SCHEDULED', 'RETRY')),
    planned_on  date        NOT NULL,
    status      text        NOT NULL CHECK (status IN ('PLANNED', 'REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    reason      text,
    paypal_ref  text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (loan_id, version, seq, attempt_no)
);
CREATE INDEX collection_attempt_planned ON lending.collection_attempt (loan_id) WHERE status = 'PLANNED';

CREATE TABLE lending.loan_timer (
    loan_id      uuid     NOT NULL REFERENCES lending.loan (id),
    kind         text     NOT NULL CHECK (kind IN
                     ('REMIND_DUE_SOON', 'WATCHDOG', 'GRACE_NUDGE', 'GRACE_EXPIRY', 'BUCKET_BOUNDARY', 'RETRY_CAPTURE')),
    ref          text     NOT NULL,
    fire_on      date     NOT NULL,
    shard        smallint NOT NULL CHECK (shard BETWEEN 0 AND 63),
    fired_on     date,    -- set once fired: the row stays as the record that it fired, and is never planned again
    PRIMARY KEY (loan_id, kind, ref)
);
-- the close-of-business work queue: only unfired timers, by date and shard
CREATE INDEX loan_timer_due ON lending.loan_timer (fire_on, shard, loan_id) WHERE fired_on IS NULL;

CREATE TABLE lending.cob_run (
    business_date date        PRIMARY KEY,
    status        text        NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'DEGRADED')),
    mode          text        NOT NULL CHECK (mode IN ('TIMERS', 'SCAN')),
    shard_count   smallint    NOT NULL CHECK (shard_count BETWEEN 1 AND 64 AND 64 % shard_count = 0),
    loans_processed int       NOT NULL DEFAULT 0,
    loans_failed  int         NOT NULL DEFAULT 0,
    trigger_ref   text        NOT NULL,
    started_at    timestamptz NOT NULL DEFAULT now(),
    finished_at   timestamptz
);

-- ---------------------------------------------------------------------------------------------
-- Append-only enforcement
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION lending.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END $$;

CREATE TRIGGER schedule_version_append_only BEFORE UPDATE OR DELETE ON lending.schedule_version
    FOR EACH ROW EXECUTE FUNCTION lending.reject_mutation();
CREATE TRIGGER instalment_append_only BEFORE UPDATE OR DELETE ON lending.instalment
    FOR EACH ROW EXECUTE FUNCTION lending.reject_mutation();
CREATE TRIGGER loan_transaction_append_only BEFORE UPDATE OR DELETE ON lending.loan_transaction
    FOR EACH ROW EXECUTE FUNCTION lending.reject_mutation();
CREATE TRIGGER instalment_missed_append_only BEFORE UPDATE OR DELETE ON lending.instalment_missed
    FOR EACH ROW EXECUTE FUNCTION lending.reject_mutation();
CREATE TRIGGER bucket_transition_append_only BEFORE UPDATE OR DELETE ON lending.bucket_transition
    FOR EACH ROW EXECUTE FUNCTION lending.reject_mutation();
