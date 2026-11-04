-- When PayPal will bill a savings subscription next (ADR 0027, closing pass). It comes from PayPal's own
-- billing_info.next_billing_time: stored from the subscription webhooks and refreshed after each deposit sale, so the
-- member's Home page can say when the next deposit is due without a call to PayPal. Null while nothing is scheduled
-- (not yet approved, suspended, ended) and until PayPal first reports it.
ALTER TABLE payments.savings_subscription ADD COLUMN next_billing_time timestamptz;
