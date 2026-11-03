-- When Seed last read a payout from PayPal itself (ADR 0029): the desk's "Check again", or the reconcile job for a payout whose
-- webhook never came. It limits how often a payout is read; it is not part of what the payout is, so the payout guard lets it change.
ALTER TABLE payments.payout ADD COLUMN last_synced_at timestamptz;

-- Payouts the reconcile job may still have to read: not final, and PayPal has given an id to read them by.
CREATE INDEX payout_to_reconcile ON payments.payout (last_synced_at NULLS FIRST, created_at)
    WHERE status IN ('REQUESTED', 'PENDING', 'UNCLAIMED') AND (paypal_item_id IS NOT NULL OR paypal_batch_id IS NOT NULL);

-- A status that Seed learned by reading PayPal is a third source next to the API response and the webhook.
ALTER TABLE payments.payout_transition DROP CONSTRAINT payout_transition_source_check;
ALTER TABLE payments.payout_transition ADD CONSTRAINT payout_transition_source_check
    CHECK (source IN ('API', 'WEBHOOK', 'POLL'));
