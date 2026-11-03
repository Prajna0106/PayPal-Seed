-- When Seed last read the goal's PayPal subscription itself (ADR 0028): a member's refresh, or the reconcile job for a
-- goal whose webhook never came. It limits how often a goal is read; it is not part of what the goal is.
ALTER TABLE savings.savings_goal ADD COLUMN last_synced_at timestamptz;

CREATE INDEX savings_goal_to_reconcile ON savings.savings_goal (last_synced_at NULLS FIRST)
    WHERE status IN ('PENDING_APPROVAL', 'ACTIVE', 'PAUSED') AND paypal_subscription_id IS NOT NULL;
