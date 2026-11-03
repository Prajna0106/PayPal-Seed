-- Money held for a member on a loan that never stood (voided payout, cooling-off cancel) and the refund owed to them
-- (ADR 0024, closing pass). The row is the durable promise: it is written in the transaction that holds the money, so a
-- PayPal that cannot refund yet leaves a retryable row behind instead of a log line.
-- The reference is the idempotency key of the refund (the payments module turns it into the PayPal-Request-Id), so one
-- reference is one refund however often it is attempted.
CREATE TABLE lending.held_refund (
    reference       text        PRIMARY KEY,
    loan_id         uuid        NOT NULL REFERENCES lending.loan (id),
    member_id       uuid        NOT NULL,
    amount_cents    bigint      NOT NULL CHECK (amount_cents > 0),
    status          text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'REFUNDED', 'FAILED')),
    attempts        int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error      text,
    next_attempt_at timestamptz NOT NULL,
    refund_ids      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX held_refund_due ON lending.held_refund (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX held_refund_loan ON lending.held_refund (loan_id);
