-- A delivered loan payout that PayPal took back (ADR 0024): the member never kept the money, so the loan is void.
-- The loan row stays (append-only facts point at it) but is closed with its own terminal status.
ALTER TABLE lending.loan DROP CONSTRAINT loan_status_check;
ALTER TABLE lending.loan ADD CONSTRAINT loan_status_check CHECK (status IN
    ('ACTIVE', 'REPAID', 'COVERED', 'CANCELLED', 'DISBURSEMENT_RETURNED'));

-- How many loans of this application were voided. The next disbursement registers a new loan, so it needs a new
-- disbursement reference (loan-<id>-disb-v<n>): the first reference is spent on the voided loan.
ALTER TABLE lending.loan_application ADD COLUMN disbursement_voids int NOT NULL DEFAULT 0 CHECK (disbursement_voids >= 0);
