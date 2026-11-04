-- The fair-check consent joins the member's consent list (ADR 0026). Only the allowed set of types grows; the
-- ledger rows and the guard trigger are untouched.
ALTER TABLE identity.consent DROP CONSTRAINT consent_type_valid;
ALTER TABLE identity.consent ADD CONSTRAINT consent_type_valid
    CHECK (type IN ('TERMS', 'PRIVACY', 'INCOME_READ', 'RECORD_SHARING', 'FAIR_CHECK'));
