-- F4 PayPal collection: mandates, the command log of every PayPal write, the webhook inbox and the
-- registry of money PayPal moved for loans (04-paypal-integration.md).

-- Every PayPal write is one row; idempotency_key is also sent as PayPal-Request-Id (04 §8).
CREATE TABLE payments.paypal_command (
    id              uuid        PRIMARY KEY,
    kind            text        NOT NULL CHECK (kind IN (
                        'CREATE_SUBSCRIPTION', 'REVISE_SUBSCRIPTION', 'SUSPEND_SUBSCRIPTION', 'ACTIVATE_SUBSCRIPTION',
                        'CANCEL_SUBSCRIPTION', 'CAPTURE_OUTSTANDING', 'CREATE_ORDER', 'CAPTURE_ORDER', 'REFUND')),
    aggregate_id    uuid        NOT NULL,          -- loan id
    idempotency_key text        NOT NULL UNIQUE,
    request_hash    text        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED_RETRYABLE', 'FAILED_FINAL')),
    attempts        int         NOT NULL DEFAULT 0,
    last_error      text,
    response        jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX paypal_command_by_aggregate ON payments.paypal_command (aggregate_id, created_at);

CREATE TABLE payments.collection_mandate (
    id                     uuid        PRIMARY KEY,
    loan_id                uuid        NOT NULL,
    member_id              uuid        NOT NULL,
    paypal_subscription_id text        UNIQUE,
    status                 text        NOT NULL CHECK (status IN ('APPROVAL_PENDING', 'ACTIVE', 'SUSPENDED', 'CANCELLED', 'EXPIRED')),
    quantity               int         NOT NULL CHECK (quantity >= 1),
    next_billing_date      date,
    approve_url            text,
    cancel_requested       boolean     NOT NULL DEFAULT false,  -- distinguishes our cancellation from the member's
    observed_at            timestamptz,
    last_desired           jsonb,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now()
);
-- at most one live (or pending) mandate per loan
CREATE UNIQUE INDEX collection_mandate_one_live ON payments.collection_mandate (loan_id)
    WHERE status IN ('APPROVAL_PENDING', 'ACTIVE', 'SUSPENDED');

CREATE TABLE payments.webhook_inbox (
    id              uuid        PRIMARY KEY,
    paypal_event_id text        NOT NULL UNIQUE,   -- delivery dedupe; money dedupe is captured_payment
    event_type      text        NOT NULL,
    payload         jsonb       NOT NULL,
    status          text        NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSED', 'PARKED', 'IGNORED', 'FAILED')),
    attempts        int         NOT NULL DEFAULT 0,
    wait_for        text,                          -- e.g. 'subscription:I-ABC' while PARKED
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    received_at     timestamptz NOT NULL DEFAULT now(),
    processed_at    timestamptz
);
CREATE INDEX webhook_inbox_work ON payments.webhook_inbox (next_attempt_at) WHERE status IN ('RECEIVED', 'PARKED');

CREATE TABLE payments.paypal_order (
    paypal_order_id text        PRIMARY KEY,
    loan_id         uuid        NOT NULL,
    purpose         text        NOT NULL CHECK (purpose IN ('CATCH_UP', 'EARLY', 'PAYOFF', 'PAY_WHAT_YOU_CAN')),
    amount_cents    bigint      NOT NULL CHECK (amount_cents > 0),
    status          text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- Money PayPal moved in for a loan. Resolves refunds and reversals to their loan, and bounds refunds.
CREATE TABLE payments.captured_payment (
    paypal_txn_id  text        PRIMARY KEY,
    loan_id        uuid        NOT NULL,
    kind           text        NOT NULL CHECK (kind IN ('SALE', 'CAPTURE')),
    amount_cents   bigint      NOT NULL CHECK (amount_cents > 0),
    refunded_cents bigint      NOT NULL DEFAULT 0 CHECK (refunded_cents BETWEEN 0 AND amount_cents),
    occurred_at    timestamptz NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX captured_payment_by_loan ON payments.captured_payment (loan_id, occurred_at DESC);

CREATE TABLE payments.refund (
    paypal_refund_id text        PRIMARY KEY,
    loan_id          uuid        NOT NULL,
    parent_txn_id    text        NOT NULL REFERENCES payments.captured_payment (paypal_txn_id),
    amount_cents     bigint      NOT NULL CHECK (amount_cents > 0),
    purpose          text        NOT NULL CHECK (purpose IN ('OVERPAYMENT')),
    created_at       timestamptz NOT NULL DEFAULT now()
);
