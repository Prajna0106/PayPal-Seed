-- F1 Goal Savings: member savings goals, PayPal subscription links, balances, and deposit counts.

CREATE TABLE savings.savings_goal (
    id                     uuid        PRIMARY KEY,
    member_id              text        NOT NULL,
    name                   text        NOT NULL,
    currency               text        NOT NULL DEFAULT 'USD',
    target_amount_cents    bigint      NOT NULL CHECK (target_amount_cents > 0),
    monthly_amount_cents   bigint      NOT NULL CHECK (monthly_amount_cents >= 500),
    target_date            date,
    paypal_subscription_id text,
    status                 text        NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED')),
    current_balance_cents  bigint      NOT NULL DEFAULT 0 CHECK (current_balance_cents >= 0),
    deposit_count          int         NOT NULL DEFAULT 0 CHECK (deposit_count >= 0),
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX savings_goal_by_member ON savings.savings_goal (member_id, created_at);
CREATE INDEX savings_goal_by_subscription ON savings.savings_goal (paypal_subscription_id) WHERE paypal_subscription_id IS NOT NULL;
