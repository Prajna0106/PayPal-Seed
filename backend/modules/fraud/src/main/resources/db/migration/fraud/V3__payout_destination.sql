-- Where the member's payout goes now, as a hash (ADR 0021). It is state, not history: it lets the rules tell a real change
-- of destination from a profile update that left it alone. Each real change is written as a PAYOUT_RECIPIENT_CHANGED fact;
-- the first destination seen for a member is only the baseline and is never a change.
CREATE TABLE fraud.payout_destination (
    member_id   uuid        PRIMARY KEY,
    ref_hash    text        NOT NULL CHECK (ref_hash ~ '^[0-9a-f]{64}$'),   -- HMAC-SHA256; never the identifier
    observed_at timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);
