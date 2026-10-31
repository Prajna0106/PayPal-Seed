-- Double-entry ledger (plan §10 7a, ADR 0002). Append-only: corrections are new entries, never edits.
-- Balances are computed from lines; no balance column exists to drift.

CREATE TABLE ledger.account (
    id          uuid        PRIMARY KEY,
    type        text        NOT NULL CHECK (type IN (
                    'PROGRAM_CASH', 'PAYPAL_FEES', 'LOANS_RECEIVABLE', 'MEMBER_OVERPAYMENT',
                    'MEMBER_SAVINGS', 'LOSS_RESERVE', 'COVERED_LOSSES')),
    owner_id    uuid,
    normal_side text        NOT NULL CHECK (normal_side IN ('DEBIT', 'CREDIT')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- program-level accounts have no owner; loan and member sub-accounts always do
    CONSTRAINT account_owner_scope CHECK (
        (type IN ('PROGRAM_CASH', 'PAYPAL_FEES', 'LOSS_RESERVE', 'COVERED_LOSSES')) = (owner_id IS NULL)),
    CONSTRAINT account_one_per_owner UNIQUE NULLS NOT DISTINCT (type, owner_id)
);

CREATE TABLE ledger.journal_entry (
    id            uuid        PRIMARY KEY,
    source_key    text        NOT NULL UNIQUE,   -- PayPal transaction id or business event id: posting twice is impossible
    request_hash  text        NOT NULL,          -- detects a source key reused for a different posting
    description   text        NOT NULL,
    business_date date        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ledger.journal_line (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id     uuid   NOT NULL REFERENCES ledger.journal_entry (id),
    account_id   uuid   NOT NULL REFERENCES ledger.account (id),
    side         text   NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount_cents bigint NOT NULL CHECK (amount_cents > 0)
);
CREATE INDEX journal_line_by_entry ON ledger.journal_line (entry_id);
-- covering index: an account balance is an index-only scan
CREATE INDEX journal_line_by_account ON ledger.journal_line (account_id) INCLUDE (side, amount_cents);

-- Every entry has at least two lines and balances. Deferred to commit, so lines can be inserted
-- one by one inside the posting transaction; checked from both tables so neither an entry without
-- lines nor a line added to an existing entry can slip through.
CREATE FUNCTION ledger.assert_balanced(p_entry uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    v_debits  bigint;
    v_credits bigint;
    v_lines   int;
BEGIN
    SELECT COALESCE(SUM(amount_cents) FILTER (WHERE side = 'DEBIT'), 0),
           COALESCE(SUM(amount_cents) FILTER (WHERE side = 'CREDIT'), 0),
           COUNT(*)
      INTO v_debits, v_credits, v_lines
      FROM ledger.journal_line WHERE entry_id = p_entry;
    IF v_lines < 2 OR v_debits <> v_credits THEN
        RAISE EXCEPTION 'journal entry % is not balanced (lines %, debits %, credits %)',
            p_entry, v_lines, v_debits, v_credits USING ERRCODE = 'check_violation';
    END IF;
END $$;

CREATE FUNCTION ledger.check_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM ledger.assert_balanced(NEW.id);
    RETURN NULL;
END $$;

CREATE FUNCTION ledger.check_line_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM ledger.assert_balanced(NEW.entry_id);
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT ON ledger.journal_entry DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_balanced();

CREATE CONSTRAINT TRIGGER journal_line_balanced
    AFTER INSERT ON ledger.journal_line DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.check_line_entry_balanced();

-- Append-only, enforced by the database rather than by convention.
CREATE FUNCTION ledger.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on %.% is not allowed', TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END $$;

CREATE TRIGGER journal_entry_append_only BEFORE UPDATE OR DELETE ON ledger.journal_entry
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER journal_line_append_only BEFORE UPDATE OR DELETE ON ledger.journal_line
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER journal_entry_no_truncate BEFORE TRUNCATE ON ledger.journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();
CREATE TRIGGER journal_line_no_truncate BEFORE TRUNCATE ON ledger.journal_line
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_mutation();
