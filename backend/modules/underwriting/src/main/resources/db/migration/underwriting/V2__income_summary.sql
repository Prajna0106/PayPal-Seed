-- F3 slice used by F5: the member's consented PayPal income, kept only as weekly totals.
-- No counterparties, no transaction ids: the hardship scorecard needs the shape of the income, not its detail.

CREATE TABLE underwriting.income_consent (
    member_id        uuid        PRIMARY KEY,
    granted_at       timestamptz NOT NULL,
    revoked_at       timestamptz,
    usual_payday_dow smallint    CHECK (usual_payday_dow BETWEEN 1 AND 7),  -- ISO day of week, from the timing profile
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE underwriting.income_week (
    member_id    uuid   NOT NULL,
    week_start   date   NOT NULL,
    inflow_cents bigint NOT NULL CHECK (inflow_cents >= 0),
    PRIMARY KEY (member_id, week_start)
);
