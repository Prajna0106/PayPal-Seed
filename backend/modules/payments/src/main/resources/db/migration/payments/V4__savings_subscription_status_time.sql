-- When the stored status of a savings subscription was last reported (ADR 0027). PayPal does not deliver webhooks in
-- order: a SUSPENDED sent before a resume can arrive after it, and the status must not move back. A report is applied
-- only if it is not older than this time; CANCELLED and EXPIRED are final whatever their time.
ALTER TABLE payments.savings_subscription ADD COLUMN status_at timestamptz;
