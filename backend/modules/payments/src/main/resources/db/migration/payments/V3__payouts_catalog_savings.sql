-- PayPal side of savings and disbursement (ADR 0013): the billing catalog cache, savings
-- subscriptions and top-up orders, and outgoing payouts with an append-only transition log.

-- New kinds of PayPal write go through the same command log as the repayment ones.
ALTER TABLE payments.paypal_command DROP CONSTRAINT paypal_command_kind_check;
ALTER TABLE payments.paypal_command ADD CONSTRAINT paypal_command_kind_check CHECK (kind IN (
    'CREATE_SUBSCRIPTION', 'REVISE_SUBSCRIPTION', 'SUSPEND_SUBSCRIPTION', 'ACTIVATE_SUBSCRIPTION',
    'CANCEL_SUBSCRIPTION', 'CAPTURE_OUTSTANDING', 'CREATE_ORDER', 'CAPTURE_ORDER', 'REFUND',
    'CREATE_PRODUCT', 'CREATE_PLAN', 'CREATE_SAVINGS_SUBSCRIPTION', 'CREATE_TOPUP_ORDER', 'CAPTURE_TOPUP_ORDER',
    'CREATE_PAYOUT'));

CREATE FUNCTION payments.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END $$;

-- Ids of the PayPal product and plans Seed found or created, so a normal request never lists the catalog.
CREATE TABLE payments.paypal_catalog_entry (
    kind       text        NOT NULL CHECK (kind IN ('PRODUCT', 'PLAN')),
    name       text        NOT NULL,
    paypal_id  text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, name)
);

-- A member's recurring deposit into a savings goal. The PayPal subscription is the actuator; the savings
-- module owns the goal. One row per (goal, request id) makes creation idempotent.
CREATE TABLE payments.savings_subscription (
    id                     uuid        PRIMARY KEY,
    goal_id                uuid        NOT NULL,
    member_id              uuid        NOT NULL,
    request_id             text        NOT NULL,
    monthly_cents          bigint      NOT NULL CHECK (monthly_cents >= 100 AND monthly_cents % 100 = 0),
    paypal_subscription_id text        NOT NULL UNIQUE,
    approve_url            text,
    status                 text        NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    UNIQUE (goal_id, request_id)
);
CREATE INDEX savings_subscription_by_goal ON payments.savings_subscription (goal_id, created_at DESC);
CREATE TRIGGER savings_subscription_no_delete BEFORE DELETE ON payments.savings_subscription
    FOR EACH ROW EXECUTE FUNCTION payments.reject_mutation();

-- A one-off top-up into a goal through Orders (PayPal or Venmo funding). Subscriptions cannot take Venmo.
CREATE TABLE payments.savings_topup (
    paypal_order_id   text        PRIMARY KEY,
    member_id         uuid        NOT NULL,
    goal_id           uuid        NOT NULL,
    amount_cents      bigint      NOT NULL CHECK (amount_cents > 0),
    funding_source    text        NOT NULL CHECK (funding_source IN ('PAYPAL', 'VENMO')),
    status            text        NOT NULL,
    paypal_capture_id text        UNIQUE,   -- the money identity; set once, so a capture is announced once
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX savings_topup_by_goal ON payments.savings_topup (goal_id, created_at DESC);
CREATE TRIGGER savings_topup_no_delete BEFORE DELETE ON payments.savings_topup
    FOR EACH ROW EXECUTE FUNCTION payments.reject_mutation();

-- Money going out. reference_id is the caller's idempotency key (for example a disbursement reference).
CREATE TABLE payments.payout (
    id                    uuid        PRIMARY KEY,
    reference_id          text        NOT NULL UNIQUE,
    sender_batch_id       text        NOT NULL UNIQUE,
    amount_cents          bigint      NOT NULL CHECK (amount_cents > 0),
    recipient_type        text        NOT NULL CHECK (recipient_type IN ('PAYPAL_ID', 'EMAIL', 'PHONE')),
    receiver              text        NOT NULL,
    wallet                text        NOT NULL CHECK (wallet IN ('PAYPAL', 'VENMO')),
    note                  text,
    status                text        NOT NULL CHECK (status IN
                              ('REQUESTED', 'PENDING', 'SUCCEEDED', 'FAILED', 'RETURNED', 'BLOCKED', 'UNCLAIMED')),
    paypal_batch_id       text        UNIQUE,
    paypal_item_id        text        UNIQUE,
    paypal_transaction_id text,
    fee_cents             bigint      NOT NULL DEFAULT 0 CHECK (fee_cents >= 0),
    failure_reason        text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now()
);

-- Every status change, in order. Never rewritten.
CREATE TABLE payments.payout_transition (
    id              uuid        PRIMARY KEY,
    payout_id       uuid        NOT NULL REFERENCES payments.payout (id),
    from_status     text,
    to_status       text        NOT NULL,
    reason          text,
    source          text        NOT NULL CHECK (source IN ('API', 'WEBHOOK')),
    paypal_event_id text,
    occurred_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payout_transition_by_payout ON payments.payout_transition (payout_id, occurred_at);
CREATE TRIGGER payout_transition_append_only BEFORE UPDATE OR DELETE ON payments.payout_transition
    FOR EACH ROW EXECUTE FUNCTION payments.reject_mutation();

-- A payout is a fact about money that left: it is never deleted, what was asked never changes, and its
-- status only moves along the paths PayPal can actually take.
CREATE FUNCTION payments.guard_payout() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'payments.payout is append-only: DELETE is not allowed' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.reference_id <> OLD.reference_id OR NEW.sender_batch_id <> OLD.sender_batch_id
       OR NEW.amount_cents <> OLD.amount_cents OR NEW.recipient_type <> OLD.recipient_type
       OR NEW.receiver <> OLD.receiver OR NEW.wallet <> OLD.wallet THEN
        RAISE EXCEPTION 'payments.payout: the request cannot be changed after it was made' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'REQUESTED' AND NEW.status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'BLOCKED', 'UNCLAIMED', 'RETURNED'))
        OR (OLD.status = 'PENDING'   AND NEW.status IN ('SUCCEEDED', 'FAILED', 'BLOCKED', 'UNCLAIMED', 'RETURNED'))
        OR (OLD.status = 'UNCLAIMED' AND NEW.status IN ('SUCCEEDED', 'FAILED', 'RETURNED'))
        OR (OLD.status = 'SUCCEEDED' AND NEW.status = 'RETURNED')) THEN
        RAISE EXCEPTION 'payments.payout: % to % is not a valid change', OLD.status, NEW.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER payout_guard BEFORE UPDATE OR DELETE ON payments.payout
    FOR EACH ROW EXECUTE FUNCTION payments.guard_payout();
