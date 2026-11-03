-- ADR 0025 follow-up: a loan whose disbursement payout came back is void and leaves the record (ADR 0024). The fact
-- store stays append-only; the void is one more fact, and the fold rebuilds the member's state without that loan.
ALTER TABLE creditrecord.record_fact DROP CONSTRAINT record_fact_kind_check;
ALTER TABLE creditrecord.record_fact DROP CONSTRAINT fact_shape;
ALTER TABLE creditrecord.record_fact ADD CONSTRAINT record_fact_kind_check CHECK (kind IN ('SAVINGS_DEPOSIT',
    'SAVING_MONTHS_CLOSED', 'LOAN_DISBURSED', 'INSTALMENT_PAID', 'INSTALMENT_MISSED', 'LOAN_REPAID', 'LOAN_DEFAULTED',
    'HARDSHIP_PLAN_APPLIED', 'INSTALMENT_FUND_COVERED', 'LOAN_VOIDED'));
ALTER TABLE creditrecord.record_fact ADD CONSTRAINT fact_shape CHECK (CASE kind
    WHEN 'INSTALMENT_PAID'         THEN loan_id IS NOT NULL AND inst_seq IS NOT NULL AND days_late IS NOT NULL
    WHEN 'INSTALMENT_MISSED'       THEN loan_id IS NOT NULL AND inst_seq IS NOT NULL
    WHEN 'INSTALMENT_FUND_COVERED' THEN loan_id IS NOT NULL AND inst_seq IS NOT NULL
    WHEN 'LOAN_REPAID'             THEN loan_id IS NOT NULL AND missed_count IS NOT NULL
    WHEN 'LOAN_DISBURSED'          THEN loan_id IS NOT NULL
    WHEN 'LOAN_DEFAULTED'          THEN loan_id IS NOT NULL
    WHEN 'LOAN_VOIDED'             THEN loan_id IS NOT NULL
    WHEN 'HARDSHIP_PLAN_APPLIED'   THEN case_id IS NOT NULL
    ELSE true END);

-- The history page hides a voided loan's facts: find them by loan.
CREATE INDEX record_fact_by_loan ON creditrecord.record_fact (loan_id) WHERE loan_id IS NOT NULL;
