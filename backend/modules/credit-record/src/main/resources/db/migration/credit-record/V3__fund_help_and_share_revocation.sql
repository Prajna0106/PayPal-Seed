-- ADR 0025: help from the safety fund neither counts against a member nor lifts their record.

-- A new fact: the fund paid all or part of one instalment. The fold keeps who paid what in its 'settled' keys, so
-- member_record needs no new column; only the fact store learns the new kind.
ALTER TABLE creditrecord.record_fact DROP CONSTRAINT record_fact_kind_check;
ALTER TABLE creditrecord.record_fact DROP CONSTRAINT fact_shape;
ALTER TABLE creditrecord.record_fact ADD CONSTRAINT record_fact_kind_check CHECK (kind IN ('SAVINGS_DEPOSIT',
    'SAVING_MONTHS_CLOSED', 'LOAN_DISBURSED', 'INSTALMENT_PAID', 'INSTALMENT_MISSED', 'LOAN_REPAID', 'LOAN_DEFAULTED',
    'HARDSHIP_PLAN_APPLIED', 'INSTALMENT_FUND_COVERED'));
ALTER TABLE creditrecord.record_fact ADD CONSTRAINT fact_shape CHECK (CASE kind
    WHEN 'INSTALMENT_PAID'         THEN loan_id IS NOT NULL AND inst_seq IS NOT NULL AND days_late IS NOT NULL
    WHEN 'INSTALMENT_MISSED'       THEN loan_id IS NOT NULL AND inst_seq IS NOT NULL
    WHEN 'INSTALMENT_FUND_COVERED' THEN loan_id IS NOT NULL AND inst_seq IS NOT NULL
    WHEN 'LOAN_REPAID'             THEN loan_id IS NOT NULL AND missed_count IS NOT NULL
    WHEN 'LOAN_DISBURSED'          THEN loan_id IS NOT NULL
    WHEN 'LOAN_DEFAULTED'          THEN loan_id IS NOT NULL
    WHEN 'HARDSHIP_PLAN_APPLIED'   THEN case_id IS NOT NULL
    ELSE true END);

-- How many loans the member completed with the fund's help, frozen with the rest of a share's snapshot. A count only:
-- it says nothing about why help was needed. Snapshots made before this migration read 0.
ALTER TABLE creditrecord.record_snapshot
    ADD COLUMN loans_fund_help int NOT NULL DEFAULT 0 CHECK (loans_fund_help >= 0);

-- Revoking every active share of one member (consent withdrawn) scans by member and revocation state.
CREATE INDEX record_share_active_by_member ON creditrecord.record_share (member_id) WHERE revoked_at IS NULL;
