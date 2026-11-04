-- ADR 0024 follow-up: a loan whose payout PayPal took back is void, and its application is approved again for another
-- payout. Lending's application snapshot (LoanApplicationChanged) carries no payout issue, so the fact that a payout
-- came back is kept here, keyed by the application. The queue shows the issue while the application is APPROVED, and
-- derives it from this row on read, so it does not matter whether the snapshot or LoanVoided is applied first, and it
-- disappears on its own when the application is paid again or rejected. Written by an idempotent upsert.
CREATE TABLE deskquery.application_void (
    application_id   uuid        PRIMARY KEY,
    loan_id          uuid        NOT NULL,
    payout_reference text        NOT NULL,
    voided_on        date        NOT NULL,
    updated_at       timestamptz NOT NULL DEFAULT now()
);
