-- PayPal webhooks can arrive out of order. The goal remembers the time of the latest subscription state it has
-- taken in (a PayPal report, or Seed's own pause or resume), so a report that happened earlier cannot undo a
-- later one. Null until the first state is known.
ALTER TABLE savings.savings_goal ADD COLUMN subscription_state_at timestamptz;
