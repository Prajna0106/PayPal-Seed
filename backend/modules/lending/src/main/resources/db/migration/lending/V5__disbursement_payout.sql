-- The disbursement payout saga (ADR 0017): an approved application waits in DISBURSING while PayPal pays the
-- member, and the loan is registered only when the payout succeeds.

ALTER TABLE lending.loan_application DROP CONSTRAINT loan_application_status_check;
ALTER TABLE lending.loan_application ADD CONSTRAINT loan_application_status_check CHECK (status IN (
    'SUBMITTED',
    'PENDING_SECOND_APPROVAL',
    'APPROVED',
    'DISBURSING',
    'DISBURSED',
    'REJECTED',
    'CANCELLED_COOLING_OFF'
));

-- The ladder's top rung (and a credit record at TRUSTED or above) allows 18 months; V4 stopped at 12, so such an
-- application could be sized and then refused by the database.
ALTER TABLE lending.loan_application DROP CONSTRAINT loan_application_term_months_check;
ALTER TABLE lending.loan_application ADD CONSTRAINT loan_application_term_months_check CHECK (term_months BETWEEN 3 AND 18);

-- Why the last payout did not end the application as disbursed (a PayPal reason name, never free text from a member).
ALTER TABLE lending.loan_application ADD COLUMN payout_issue    text;
-- How many payouts were asked of PayPal. A payout PayPal refused spends its reference, so each retry is a new one.
ALTER TABLE lending.loan_application ADD COLUMN payout_attempts int NOT NULL DEFAULT 0 CHECK (payout_attempts >= 0);
-- The officer who last acted on the application, for the desk queue (shown only as a pseudonymous reference).
ALTER TABLE lending.loan_application ADD COLUMN last_officer_id uuid;
