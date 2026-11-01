-- F2 Savings-Linked Microloans: applications, sizing evaluation, two-officer maker-checker approvals, and 48h cooling-off window.

CREATE TABLE lending.loan_application (
    id                           uuid        PRIMARY KEY,
    member_id                    uuid        NOT NULL,
    purpose                      text        NOT NULL,
    requested_amount_cents       bigint      NOT NULL CHECK (requested_amount_cents > 0),
    term_months                  int         NOT NULL CHECK (term_months BETWEEN 3 AND 12),
    preferred_due_day            int         NOT NULL CHECK (preferred_due_day BETWEEN 1 AND 28),
    payout_wallet                text        NOT NULL CHECK (payout_wallet IN ('PAYPAL', 'VENMO')),
    fair_check_consented         boolean     NOT NULL DEFAULT true,
    unlocked_tier                text        NOT NULL,
    max_monthly_instalment_cents bigint      NOT NULL CHECK (max_monthly_instalment_cents >= 0),
    monthly_instalment_cents     bigint      NOT NULL CHECK (monthly_instalment_cents >= 0),
    sizing_reasons               jsonb       NOT NULL DEFAULT '[]'::jsonb,
    status                       text        NOT NULL CHECK (status IN (
        'SUBMITTED',
        'PENDING_SECOND_APPROVAL',
        'APPROVED',
        'DISBURSED',
        'REJECTED',
        'CANCELLED_COOLING_OFF'
    )),
    first_approver_id            uuid,
    first_approved_at            timestamptz,
    second_approver_id           uuid,
    second_approved_at           timestamptz,
    rejection_reason             text,
    loan_id                      uuid        REFERENCES lending.loan (id),
    disbursement_ref             text,
    disbursed_at                 timestamptz,
    cooling_off_expires_at       timestamptz,
    created_at                   timestamptz NOT NULL DEFAULT now(),
    updated_at                   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT loan_application_maker_checker_distinct CHECK (
        second_approver_id IS NULL OR first_approver_id <> second_approver_id
    )
);

CREATE INDEX loan_application_by_member ON lending.loan_application (member_id, created_at DESC);
CREATE INDEX loan_application_by_status ON lending.loan_application (status, created_at DESC);
