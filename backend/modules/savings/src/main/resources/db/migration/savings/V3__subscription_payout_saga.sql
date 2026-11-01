-- Savings money that really moves through PayPal (ADR 0017): a goal waits for the member to approve its
-- subscription, a withdrawal reserves the money and completes when the payout does, and every PayPal
-- payment is credited once.

ALTER TABLE savings.savings_goal DROP CONSTRAINT savings_goal_status_check;
ALTER TABLE savings.savings_goal ADD CONSTRAINT savings_goal_status_check CHECK (status IN (
    'PENDING_APPROVAL', 'ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED', 'EXPIRED'));

ALTER TABLE savings.savings_goal
    ADD COLUMN wallet              text        NOT NULL DEFAULT 'PAYPAL' CHECK (wallet IN ('PAYPAL', 'VENMO')),
    ADD COLUMN approval_url        text,
    ADD COLUMN approval_expires_at timestamptz,
    -- money held for withdrawals whose payout has not finished; it has left current_balance_cents already
    ADD COLUMN reserved_cents      bigint      NOT NULL DEFAULT 0 CHECK (reserved_cents >= 0),
    -- bumped by every pause, resume and amount change so each PayPal call has its own request id
    ADD COLUMN change_seq          int         NOT NULL DEFAULT 0,
    ADD COLUMN create_key          text;

-- One goal per (member, Idempotency-Key): a replay of the create request finds it instead of making another.
CREATE UNIQUE INDEX savings_goal_by_create_key ON savings.savings_goal (member_id, create_key) WHERE create_key IS NOT NULL;
CREATE INDEX savings_goal_pending_approval ON savings.savings_goal (approval_expires_at) WHERE status = 'PENDING_APPROVAL';

-- A withdrawal is the saga around one PayPal payout. Its id is the payout reference.
CREATE TABLE savings.withdrawal (
    id                    uuid        PRIMARY KEY,
    goal_id               uuid        NOT NULL REFERENCES savings.savings_goal (id),
    member_id             text        NOT NULL,
    idempotency_key       text        NOT NULL,
    amount_cents          bigint      NOT NULL CHECK (amount_cents > 0),
    wallet                text        NOT NULL CHECK (wallet IN ('PAYPAL', 'VENMO')),
    status                text        NOT NULL CHECK (status IN ('REQUESTED', 'SUCCEEDED', 'FAILED', 'RETURNED')),
    failure_reason        text,
    paypal_transaction_id text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    UNIQUE (goal_id, idempotency_key)
);
CREATE INDEX withdrawal_by_goal ON savings.withdrawal (goal_id, created_at DESC);

-- PayPal payments already credited to a goal, keyed by the PayPal transaction (or capture) id.
CREATE TABLE savings.credited_payment (
    paypal_txn_id text        PRIMARY KEY,
    goal_id       uuid        NOT NULL,
    amount_cents  bigint      NOT NULL CHECK (amount_cents > 0),
    source        text        NOT NULL CHECK (source IN ('SUBSCRIPTION', 'TOPUP')),
    credited_at   timestamptz NOT NULL DEFAULT now()
);
