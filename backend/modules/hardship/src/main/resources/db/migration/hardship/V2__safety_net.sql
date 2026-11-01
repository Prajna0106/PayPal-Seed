-- F5 hardship safety net: scorecard runs, cases, offers, member consent, narratives, timers and the
-- execution saga (02-design §5.1). Money is bigint cents; dates are business dates (America/Los_Angeles).

-- A scorecard evaluation of one loan on one scan date. UNIQUE (loan_id, scan_date) makes the scan and the
-- event wake-ups idempotent; hysteresis reads the previous run of the same instalment cycle.
CREATE TABLE hardship.scorecard_run (
    id               uuid        PRIMARY KEY,
    loan_id          uuid        NOT NULL,
    member_id        uuid        NOT NULL,
    scan_date        date        NOT NULL,
    schedule_version int         NOT NULL,
    target_seq       int         NOT NULL,
    raw_level        text        NOT NULL CHECK (raw_level IN ('NONE', 'WATCH', 'LIKELY_MISS', 'IMMINENT')),
    level            text        NOT NULL CHECK (level IN ('NONE', 'WATCH', 'LIKELY_MISS', 'IMMINENT')),
    below_count      int         NOT NULL CHECK (below_count >= 0),
    points           int         NOT NULL CHECK (points >= 0),
    features         jsonb       NOT NULL,
    policy_version   text        NOT NULL,
    scorecard_version text       NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (loan_id, scan_date)
);
CREATE INDEX scorecard_run_cycle ON hardship.scorecard_run (loan_id, target_seq, scan_date DESC);

CREATE TABLE hardship.hardship_case (
    id                  uuid        PRIMARY KEY,
    loan_id             uuid        NOT NULL,
    member_id           uuid        NOT NULL,
    origin              text        NOT NULL CHECK (origin IN ('PROACTIVE_SCAN', 'MEMBER_HELP', 'STAFF')),
    status              text        NOT NULL CHECK (status IN ('DETECTED', 'OUTREACH_SENT', 'OFFER_VIEWED', 'OPTION_CHOSEN',
                            'PENDING_APPROVAL', 'APPROVED', 'APPROVED_EXECUTION_FAILED', 'APPLIED', 'DECLINED_BY_MEMBER',
                            'REJECTED_BY_OFFICER', 'EXPIRED', 'CANCELLED_PAID', 'SUPERSEDED')),
    run_id              uuid        NOT NULL REFERENCES hardship.scorecard_run (id),
    base_version        int         NOT NULL,
    target_seq          int         NOT NULL,
    target_due_date     date        NOT NULL,
    target_amount_cents bigint      NOT NULL CHECK (target_amount_cents > 0),
    risk_level          text        NOT NULL CHECK (risk_level IN ('WATCH', 'LIKELY_MISS', 'IMMINENT')),
    points              int         NOT NULL,
    policy_version      text        NOT NULL,
    scorecard_version   text        NOT NULL,
    reason_category     text,       -- closed set only; the member's own words are never stored
    dedupe_key          text        NOT NULL UNIQUE,  -- 'loan:<id>:v<ver>:s<seq>:<origin>'
    opened_on           date        NOT NULL,
    narrative_source    text        NOT NULL DEFAULT 'TEMPLATE' CHECK (narrative_source IN ('TEMPLATE', 'AI')),
    agent_deadline_at   timestamptz,
    outreach_sent_at    timestamptz,  -- set once: the offer reaches the member exactly once per revision 1
    reminder_sent_at    timestamptz,
    chosen_option       text        CHECK (chosen_option IN ('A', 'B', 'C', 'D')),
    approval_request_id uuid,
    applied_version     int,
    closed_on           date,
    closed_reason       text,
    cooldown_until      date,         -- closed_on + 14 days for proactive cases; a member's HELP is never blocked
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    row_version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT case_closed_state CHECK ((closed_on IS NULL) = (status NOT IN ('APPLIED', 'DECLINED_BY_MEMBER',
        'REJECTED_BY_OFFICER', 'EXPIRED', 'CANCELLED_PAID', 'SUPERSEDED')))
);
-- HINV: at most one open case per loan, whatever path tries to open a second one.
CREATE UNIQUE INDEX case_one_open_per_loan ON hardship.hardship_case (loan_id)
    WHERE status NOT IN ('APPLIED', 'DECLINED_BY_MEMBER', 'REJECTED_BY_OFFICER', 'EXPIRED', 'CANCELLED_PAID', 'SUPERSEDED');
CREATE INDEX case_by_member ON hardship.hardship_case (member_id, opened_on DESC);
CREATE INDEX case_by_loan ON hardship.hardship_case (loan_id, opened_on DESC);
CREATE INDEX case_queue ON hardship.hardship_case (status, id DESC);
CREATE UNIQUE INDEX case_by_approval ON hardship.hardship_case (approval_request_id) WHERE approval_request_id IS NOT NULL;

CREATE TABLE hardship.signal (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id       uuid        NOT NULL REFERENCES hardship.scorecard_run (id),
    code         text        NOT NULL,
    points       int         NOT NULL CHECK (points >= 0),
    evidence     jsonb       NOT NULL,  -- numbers only: what the grounding checker compares against
    source       text        NOT NULL CHECK (source IN ('SCORECARD', 'AI_SECOND_OPINION', 'MEMBER')),
    agent_run_id uuid,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, code),
    CONSTRAINT ai_signal_has_run CHECK ((source = 'AI_SECOND_OPINION') = (agent_run_id IS NOT NULL))
);

CREATE TABLE hardship.offer (
    id           uuid        PRIMARY KEY,
    case_id      uuid        NOT NULL REFERENCES hardship.hardship_case (id),
    revision     int         NOT NULL CHECK (revision >= 1),
    base_version int         NOT NULL,
    issued_on    date        NOT NULL,
    expires_on   date        NOT NULL,
    recommended  text        CHECK (recommended IN ('A', 'B', 'C', 'D')),
    status       text        NOT NULL CHECK (status IN ('ACTIVE', 'STALE', 'EXPIRED', 'ACCEPTED', 'SUPERSEDED')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (case_id, revision)
);
CREATE UNIQUE INDEX offer_one_active ON hardship.offer (case_id) WHERE status = 'ACTIVE';

-- What the member was shown, frozen: the snapshot hash ties the choice, the approval and the apply together.
CREATE TABLE hardship.offer_option (
    id                   uuid        PRIMARY KEY,
    offer_id             uuid        NOT NULL REFERENCES hardship.offer (id),
    label                text        NOT NULL CHECK (label IN ('A', 'B', 'C', 'D')),
    primitive            text        NOT NULL CHECK (primitive IN ('MOVE_DATE', 'DEFER_TO_END', 'REAMORTIZE', 'PAYMENT')),
    args                 jsonb       NOT NULL,
    proposed             jsonb       NOT NULL,  -- [{seq, dueDate, amountCents, remainingCents}] from the engine preview
    summary              jsonb       NOT NULL,
    snapshot_hash        text        NOT NULL,
    mean_delay_days      numeric(7, 2) NOT NULL,
    max_instalment_cents bigint      NOT NULL,
    burden_bps           int,
    sustainable          boolean,
    recommended          boolean     NOT NULL,
    tier                 text        NOT NULL CHECK (tier IN ('AUTO_POLICY', 'MAKER_CHECKER', 'NONE')),
    warnings             jsonb       NOT NULL,
    UNIQUE (offer_id, label),
    CONSTRAINT burden_and_sustainable_together CHECK ((burden_bps IS NULL) = (sustainable IS NULL))
);
CREATE UNIQUE INDEX offer_one_recommended ON hardship.offer_option (offer_id) WHERE recommended;

-- Member consent: one stored record per decision, keyed for idempotent taps and SMS replays.
CREATE TABLE hardship.choice (
    id                   uuid        PRIMARY KEY,
    case_id              uuid        NOT NULL REFERENCES hardship.hardship_case (id),
    offer_id             uuid        NOT NULL REFERENCES hardship.offer (id),
    option_id            uuid        REFERENCES hardship.offer_option (id),
    kind                 text        NOT NULL CHECK (kind IN ('CHOOSE', 'DECLINE')),
    member_id            uuid        NOT NULL,
    channel              text        NOT NULL CHECK (channel IN ('SMS', 'APP')),
    consent_text_version text        NOT NULL,
    idempotency_key      text        NOT NULL UNIQUE,
    chosen_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT choose_has_option CHECK ((kind = 'CHOOSE') = (option_id IS NOT NULL))
);
CREATE UNIQUE INDEX choice_one_per_offer ON hardship.choice (offer_id) WHERE kind = 'CHOOSE';

CREATE TABLE hardship.narrative (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id      uuid        NOT NULL REFERENCES hardship.hardship_case (id),
    audience     text        NOT NULL CHECK (audience IN ('MEMBER', 'OFFICER')),
    locale       text        NOT NULL CHECK (locale IN ('en', 'es')),
    text         text        NOT NULL,
    source       text        NOT NULL CHECK (source IN ('TEMPLATE', 'AI', 'AI_SHADOW')),
    grounding    text        NOT NULL CHECK (grounding IN ('PASSED', 'NOT_RUN')),
    agent_run_id uuid,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ai_text_was_grounded CHECK (source = 'TEMPLATE' OR (grounding = 'PASSED' AND agent_run_id IS NOT NULL))
);
CREATE INDEX narrative_by_case ON hardship.narrative (case_id, audience, locale, id DESC);

-- Durable case timers. A fired timer keeps its row; the partial index is the sweep queue.
CREATE TABLE hardship.case_timer (
    case_id  uuid        NOT NULL REFERENCES hardship.hardship_case (id),
    kind     text        NOT NULL CHECK (kind IN ('AGENT_DEADLINE', 'OFFER_REMINDER', 'OFFER_EXPIRY', 'HOLD_CHECK',
                             'SLA_50', 'SLA_80', 'SLA_BREACH', 'HOLD_EXPIRY', 'SAGA_RETRY')),
    fire_at  timestamptz NOT NULL,
    fired_at timestamptz,
    PRIMARY KEY (case_id, kind)
);
CREATE INDEX case_timer_due ON hardship.case_timer (fire_at) WHERE fired_at IS NULL;

CREATE TABLE hardship.saga (
    case_id         uuid        PRIMARY KEY REFERENCES hardship.hardship_case (id),
    approval_ref    text        NOT NULL UNIQUE,
    request_id      uuid        NOT NULL,
    option_id       uuid        NOT NULL REFERENCES hardship.offer_option (id),
    status          text        NOT NULL CHECK (status IN ('PENDING', 'DONE', 'FAILED', 'STALE')),
    attempts        int         NOT NULL DEFAULT 0,
    last_error      text,
    applied_version int,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Idempotency for inputs that arrive more than once: agent outcomes (per run) and SMS replies (per message).
CREATE TABLE hardship.agent_outcome (
    run_id        uuid        PRIMARY KEY,
    case_id       uuid        NOT NULL REFERENCES hardship.hardship_case (id),
    proposal_id   uuid,
    approvable    boolean     NOT NULL,
    shadow        boolean     NOT NULL,
    failed_checks jsonb       NOT NULL,
    error_code    text,
    received_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE hardship.sms_reply (
    message_id text        PRIMARY KEY,
    member_id  uuid        NOT NULL,
    keyword    text        NOT NULL,
    case_id    uuid,
    outcome    text        NOT NULL,
    handled_at timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION hardship.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END $$;

CREATE TRIGGER scorecard_run_append_only BEFORE UPDATE OR DELETE ON hardship.scorecard_run
    FOR EACH ROW EXECUTE FUNCTION hardship.reject_mutation();
CREATE TRIGGER signal_append_only BEFORE UPDATE OR DELETE ON hardship.signal
    FOR EACH ROW EXECUTE FUNCTION hardship.reject_mutation();
CREATE TRIGGER offer_option_append_only BEFORE UPDATE OR DELETE ON hardship.offer_option
    FOR EACH ROW EXECUTE FUNCTION hardship.reject_mutation();
CREATE TRIGGER choice_append_only BEFORE UPDATE OR DELETE ON hardship.choice
    FOR EACH ROW EXECUTE FUNCTION hardship.reject_mutation();
CREATE TRIGGER narrative_append_only BEFORE UPDATE OR DELETE ON hardship.narrative
    FOR EACH ROW EXECUTE FUNCTION hardship.reject_mutation();
CREATE TRIGGER agent_outcome_append_only BEFORE UPDATE OR DELETE ON hardship.agent_outcome
    FOR EACH ROW EXECUTE FUNCTION hardship.reject_mutation();
