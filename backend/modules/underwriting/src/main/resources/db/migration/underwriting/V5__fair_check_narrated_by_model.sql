-- Records whether the plain-language reasons and officer questions were written by the underwriting agent
-- (true) or are the deterministic template sentences (false), so the desk can label the text honestly.
-- The table is not append-only: narration and review state are already updated in place by an upsert.
ALTER TABLE underwriting.fair_loan_check_assessment
    ADD COLUMN narrated_by_model boolean NOT NULL DEFAULT false;
