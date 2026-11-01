-- Income read from PayPal Transaction Search (ADR 0018). Seeded weeks leave both columns empty.
-- income_refreshed_at: when the weeks were last read from PayPal, the staleness clock.
-- income_anchor: the business date the 13 weekly buckets were cut for (the day after the last bucket ends), so the
-- summary stays aligned with the stored rows for a few days after the read.
ALTER TABLE underwriting.income_consent
    ADD COLUMN income_refreshed_at timestamptz,
    ADD COLUMN income_anchor       date;
