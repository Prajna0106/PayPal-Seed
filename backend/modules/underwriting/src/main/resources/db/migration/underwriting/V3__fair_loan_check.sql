-- F3 The Fair Loan Check: auditable assessment snapshots, reproducible inputs hash, plain-language reason cards, member disputes, and human officer decisions.

CREATE TABLE underwriting.fair_loan_check_assessment (
    id                                 uuid             PRIMARY KEY,
    member_id                          uuid             NOT NULL,
    application_id                     uuid,
    purpose                            text             NOT NULL,
    recommended                        boolean          NOT NULL,
    unlocked_tier                      text             NOT NULL,
    used_fallback_stated_income        boolean          NOT NULL,
    average_monthly_income_cents       bigint           NOT NULL,
    lowest_monthly_income_cents        bigint           NOT NULL,
    lowest_income_month                text,
    coefficient_of_variation           double precision NOT NULL,
    high_income_volatility             boolean          NOT NULL,
    proven_monthly_saving_cents        bigint           NOT NULL,
    months_saved                       int              NOT NULL,
    target_months_window               int              NOT NULL,
    instalment_cap_cents               bigint           NOT NULL,
    requested_amount_cents             bigint           NOT NULL,
    requested_term_months              int              NOT NULL,
    suggested_loan_cents               bigint           NOT NULL,
    suggested_term_months              int              NOT NULL,
    suggested_monthly_instalment_cents bigint           NOT NULL,
    inputs_hash                        text             NOT NULL,
    model_version                      text             NOT NULL,
    plain_language_reasons             jsonb            NOT NULL DEFAULT '[]'::jsonb,
    officer_questions                  jsonb            NOT NULL DEFAULT '[]'::jsonb,
    used_data                          jsonb            NOT NULL DEFAULT '[]'::jsonb,
    never_used_data                    jsonb            NOT NULL DEFAULT '[]'::jsonb,
    member_disputed                    boolean          NOT NULL DEFAULT false,
    member_dispute_note                text,
    disputed_at                        timestamptz,
    officer_decision                   text             NOT NULL DEFAULT 'PENDING' CHECK (officer_decision IN (
        'PENDING',
        'APPROVED',
        'ADJUSTED',
        'DECLINED'
    )),
    officer_id                         uuid,
    decided_loan_cents                 bigint           NOT NULL,
    decided_term_months                int              NOT NULL,
    officer_reason                     text,
    decided_at                         timestamptz,
    created_at                         timestamptz      NOT NULL DEFAULT now()
);

CREATE INDEX fair_loan_check_by_member ON underwriting.fair_loan_check_assessment (member_id, created_at DESC);
CREATE INDEX fair_loan_check_by_application ON underwriting.fair_loan_check_assessment (application_id) WHERE application_id IS NOT NULL;
