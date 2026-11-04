-- Money that went back to the fund after it was paid out (ADR 0024). Two ways it happens: a cover credited the
-- loan's receivable in the ledger for more than the schedule could take (the member paid in the same moment), and a
-- loan was voided because its payout came back (the cover on it goes back with it).

-- How much of an approved cover has since been handed back. The yearly cover cap counts amount minus this.
ALTER TABLE reserve.cover_request ADD COLUMN returned_cents bigint NOT NULL DEFAULT 0;
ALTER TABLE reserve.cover_request ADD CONSTRAINT cover_returned_within_amount CHECK (returned_cents BETWEEN 0 AND amount_cents);

-- One row per return, keyed by what makes it idempotent: the cover's approval reference, or the voided loan.
CREATE TABLE reserve.cover_return (
    return_ref       text        PRIMARY KEY,
    kind             text        NOT NULL CHECK (kind IN ('UNUSED_COVER', 'LOAN_VOIDED')),
    loan_id          uuid        NOT NULL,
    amount_cents     bigint      NOT NULL CHECK (amount_cents > 0),
    -- the entry reserve posted for an unused cover; null when the money came back in lending's own entry (a voided loan)
    journal_entry_id uuid,
    reason_code      text        NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    business_date    date        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT return_entry_only_when_posted_here CHECK ((kind = 'UNUSED_COVER') = (journal_entry_id IS NOT NULL))
);
CREATE INDEX cover_return_by_loan ON reserve.cover_return (loan_id);

CREATE TRIGGER cover_return_append_only BEFORE UPDATE OR DELETE ON reserve.cover_return
    FOR EACH ROW EXECUTE FUNCTION reserve.reject_mutation();
CREATE TRIGGER cover_return_no_truncate BEFORE TRUNCATE ON reserve.cover_return
    FOR EACH STATEMENT EXECUTE FUNCTION reserve.reject_mutation();
