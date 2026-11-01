-- Read models for the officer desk (ADR 0015). Every row is a projection of other modules' events, keyed by the
-- source business key and written by idempotent, guarded upserts, so a replayed or out-of-order event cannot
-- corrupt it. Nothing here is a source of truth: DeskQueryFacade.reset(...) empties a projection and replaying the
-- event publication log refills it. Enumerations that upstream modules own (statuses, buckets) carry no CHECK: a new
-- upstream value must never make a handler fail and block the event.

-- One row per loan. Each column group has its own "as of" guard because the events that feed the groups are
-- independent facts about the same loan.
CREATE TABLE deskquery.loan_view (
    loan_id           uuid        PRIMARY KEY,
    member_ref        uuid        NOT NULL,
    status            text        NOT NULL DEFAULT 'ACTIVE',
    -- NULL until ScheduleGenerated is seen; the grids list only rows that have it.
    principal_cents   bigint      CHECK (principal_cents >= 0),
    term_months       int         CHECK (term_months > 0),
    disbursed_on      date,
    schedule_version  int         NOT NULL DEFAULT 0,
    outstanding_cents bigint      CHECK (outstanding_cents >= 0),
    next_due_date     date,
    next_due_cents    bigint      CHECK (next_due_cents >= 0),
    days_late         int         NOT NULL DEFAULT 0 CHECK (days_late >= 0),
    aging_bucket      text        NOT NULL DEFAULT 'CURRENT',
    autopay           boolean     NOT NULL DEFAULT false,
    hardship_open     boolean     NOT NULL DEFAULT false,
    missed_instalments int        NOT NULL DEFAULT 0 CHECK (missed_instalments >= 0),
    repaid_on         date,
    repaid_on_plan    boolean     NOT NULL DEFAULT false,
    money_as_of       date,
    bucket_as_of      date,
    mode_as_of        date,
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- Plain ascending (column, id) indexes serve a sort in either direction, because the keyset tiebreak follows the
-- direction of the last sort key and the index can be read backwards.
CREATE INDEX loan_view_by_disbursed ON deskquery.loan_view (disbursed_on, loan_id) WHERE principal_cents IS NOT NULL;
CREATE INDEX loan_view_by_bucket ON deskquery.loan_view (aging_bucket, days_late, loan_id) WHERE principal_cents IS NOT NULL;
CREATE INDEX loan_view_by_status ON deskquery.loan_view (status, loan_id) WHERE principal_cents IS NOT NULL;
CREATE INDEX loan_view_by_next_due ON deskquery.loan_view (next_due_date, loan_id) WHERE principal_cents IS NOT NULL;
-- First loan of a member (DISTINCT ON in the impact figures).
CREATE INDEX loan_view_by_member ON deskquery.loan_view (member_ref, disbursed_on, loan_id) WHERE principal_cents IS NOT NULL;
CREATE INDEX loan_view_by_updated ON deskquery.loan_view (updated_at);

-- The current schedule version's instalments; replaced as a set when a newer version arrives.
CREATE TABLE deskquery.loan_instalment_view (
    loan_id      uuid   NOT NULL,
    seq          int    NOT NULL,
    due_date     date   NOT NULL,
    amount_cents bigint NOT NULL CHECK (amount_cents >= 0),
    PRIMARY KEY (loan_id, seq)
);
CREATE INDEX loan_instalment_view_by_due ON deskquery.loan_instalment_view (due_date);

-- A missed instalment is an append-only fact in lending; this set makes counting it replay-proof.
CREATE TABLE deskquery.loan_missed_view (
    loan_id uuid NOT NULL,
    version int  NOT NULL,
    seq     int  NOT NULL,
    PRIMARY KEY (loan_id, version, seq)
);

-- Hardship cases. CLOSED is terminal: an OPEN event that arrives late never reopens a case.
CREATE TABLE deskquery.hardship_case_view (
    case_id       uuid        PRIMARY KEY,
    loan_id       uuid        NOT NULL,
    member_ref    uuid        NOT NULL,
    state         text        NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'CLOSED')),
    level         text,
    origin        text,
    opened_on     date,
    closed_on     date,
    closed_reason text,
    applied       boolean     NOT NULL DEFAULT false,
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX hardship_case_view_by_loan ON deskquery.hardship_case_view (loan_id, state);
CREATE INDEX hardship_case_view_open ON deskquery.hardship_case_view (state) WHERE state = 'OPEN';

-- Saving history per member. The set of months with a deposit is what makes the streak order-independent.
CREATE TABLE deskquery.member_savings (
    member_ref     uuid        PRIMARY KEY,
    streak_months  int         NOT NULL DEFAULT 0 CHECK (streak_months >= 0),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE deskquery.member_savings_month (
    member_ref uuid NOT NULL,
    month      date NOT NULL CHECK (month = date_trunc('month', month)::date),
    PRIMARY KEY (member_ref, month)
);

-- Governance approval requests (hardship decisions). stage: 0 waits for the maker, 1 for the checker, 2 is done.
CREATE TABLE deskquery.approval_view (
    request_id uuid        PRIMARY KEY,
    subject_id uuid        NOT NULL,
    tier       text,
    state      text        NOT NULL DEFAULT 'OPEN',
    stage      int         NOT NULL DEFAULT 0 CHECK (stage BETWEEN 0 AND 2),
    sla_due_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX approval_view_open ON deskquery.approval_view (stage, sla_due_at) WHERE state = 'OPEN';

-- Loan application queue.
CREATE TABLE deskquery.application_view (
    application_id        uuid        PRIMARY KEY,
    member_ref            uuid        NOT NULL,
    status                text        NOT NULL,
    requested_amount_cents bigint     NOT NULL CHECK (requested_amount_cents > 0),
    term_months           int         NOT NULL CHECK (term_months > 0),
    saving_streak_months  int         NOT NULL DEFAULT 0 CHECK (saving_streak_months >= 0),
    assessment_outcome    text,
    submitted_at          timestamptz NOT NULL,
    sla_due_at            timestamptz NOT NULL,
    assigned_officer_ref  text,
    source_updated_at     timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX application_view_by_submitted ON deskquery.application_view (submitted_at, application_id);
CREATE INDEX application_view_by_status ON deskquery.application_view (status, submitted_at, application_id);
CREATE INDEX application_view_by_sla ON deskquery.application_view (sla_due_at, application_id)
    WHERE status IN ('SUBMITTED', 'PENDING_SECOND_APPROVAL');
CREATE INDEX application_view_by_member ON deskquery.application_view (member_ref);

-- Fair-check decisions queue.
CREATE TABLE deskquery.assessment_view (
    assessment_id           uuid        PRIMARY KEY,
    member_ref              uuid        NOT NULL,
    application_id          uuid,
    recommended             boolean     NOT NULL,
    recommended_amount_cents bigint     NOT NULL CHECK (recommended_amount_cents >= 0),
    status                  text        NOT NULL,
    outcome                 text        NOT NULL,
    disputed                boolean     NOT NULL DEFAULT false,
    created_at              timestamptz NOT NULL,
    source_updated_at       timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX assessment_view_by_created ON deskquery.assessment_view (created_at, assessment_id);
CREATE INDEX assessment_view_by_status ON deskquery.assessment_view (status, created_at, assessment_id);
CREATE INDEX assessment_view_by_application ON deskquery.assessment_view (application_id);
