-- The recipient of the member's latest payout, as a hash of a fingerprint (ADR 0027). payout_destination (V3) only knows the
-- wallet type; this knows who was paid. Payments publishes a keyed fingerprint of the recipient with every payout request,
-- so even an email that changed at a later PayPal login is seen. It is state, not history: the first recipient is the
-- baseline, the same one again records nothing, and each different one is written as a PAYOUT_RECIPIENT_CHANGED fact.
CREATE TABLE fraud.payout_recipient (
    member_id   uuid        PRIMARY KEY,
    ref_hash    text        NOT NULL CHECK (ref_hash ~ '^[0-9a-f]{64}$'),   -- HMAC-SHA256 of the fingerprint; never the recipient
    observed_at timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);
