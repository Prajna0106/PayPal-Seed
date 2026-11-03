-- What happened in a member's Seed account, as the member's Home page lists it (ADR 0027, closing pass). A read model
-- like the others in this schema: one row per business key, written by idempotent upserts from other modules' events, so
-- a repeated or replayed delivery changes nothing. Money and times only: no names, no hardship detail, no PayPal
-- reference. occurred_at is the source's instant, or the start of the business day when the source gives only a date.
CREATE TABLE deskquery.member_activity (
    activity_key text        PRIMARY KEY,
    member_ref   uuid        NOT NULL,
    kind         text        NOT NULL CHECK (kind IN ('DEPOSIT', 'WITHDRAWAL', 'WITHDRAWAL_RETURNED', 'LOAN_DISBURSED',
                                                      'LOAN_PAYMENT', 'LOAN_PAYMENT_REVERSED', 'COVER_APPLIED', 'LOAN_VOIDED')),
    amount_cents bigint      NOT NULL CHECK (amount_cents >= 0),
    occurred_at  timestamptz NOT NULL,
    goal_id      uuid,
    loan_id      uuid,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX member_activity_by_member ON deskquery.member_activity (member_ref, occurred_at DESC, activity_key DESC);

-- Rebuilds that run once at start-up and must not run again. The row is written in the transaction of the rebuild, so a
-- rebuild that failed is tried again on the next start.
CREATE TABLE deskquery.rebuild_done (
    name    text        PRIMARY KEY,
    done_at timestamptz NOT NULL DEFAULT now()
);
