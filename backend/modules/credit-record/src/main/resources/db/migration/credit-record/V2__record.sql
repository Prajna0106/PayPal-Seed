-- F6 credit record (ADR 0014): an append-only fact store, one current-state row per member, frozen snapshots and
-- signed share links. The record holds counts and dates only: no name, email, payer id or loan amount.

-- Facts as they arrived. fact_key is the business key (an event id, or loan:seq for an instalment), so a
-- redelivered event is a no-op. seq gives the order the projection folded them in; a rebuild replays by seq.
CREATE TABLE creditrecord.record_fact (
    seq          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fact_key     text        NOT NULL UNIQUE CHECK (length(fact_key) BETWEEN 1 AND 200),
    member_id    uuid        NOT NULL,
    kind         text        NOT NULL CHECK (kind IN ('SAVINGS_DEPOSIT', 'SAVING_MONTHS_CLOSED', 'LOAN_DISBURSED',
                                 'INSTALMENT_PAID', 'INSTALMENT_MISSED', 'LOAN_REPAID', 'LOAN_DEFAULTED',
                                 'HARDSHIP_PLAN_APPLIED')),
    occurred_on  date        NOT NULL,
    loan_id      uuid,
    inst_seq     int         CHECK (inst_seq IS NULL OR inst_seq >= 1),
    days_late    int         CHECK (days_late IS NULL OR days_late >= 0),
    missed_count int         CHECK (missed_count IS NULL OR missed_count >= 0),
    case_id      uuid,
    recorded_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fact_shape CHECK (CASE kind
        WHEN 'INSTALMENT_PAID'   THEN loan_id IS NOT NULL AND inst_seq IS NOT NULL AND days_late IS NOT NULL
        WHEN 'INSTALMENT_MISSED' THEN loan_id IS NOT NULL AND inst_seq IS NOT NULL
        WHEN 'LOAN_REPAID'       THEN loan_id IS NOT NULL AND missed_count IS NOT NULL
        WHEN 'LOAN_DISBURSED'    THEN loan_id IS NOT NULL
        WHEN 'LOAN_DEFAULTED'    THEN loan_id IS NOT NULL
        WHEN 'HARDSHIP_PLAN_APPLIED' THEN case_id IS NOT NULL
        ELSE true END)
);
CREATE INDEX record_fact_by_member ON creditrecord.record_fact (member_id, seq DESC);

-- The fold of a member's facts. Counters are lifetime (what a member sees and a partner is told); cur_* restart at
-- a default and are what the level is judged on. Level is stored, never recomputed on read, so a level cannot
-- drop by recomputation.
CREATE TABLE creditrecord.member_record (
    member_id                uuid        PRIMARY KEY,
    level                    smallint    NOT NULL CHECK (level BETWEEN 0 AND 4),
    since                    date        NOT NULL,
    level_since              date        NOT NULL,
    life_saving_on_time      int         NOT NULL DEFAULT 0 CHECK (life_saving_on_time >= 0),
    life_saving_missed       int         NOT NULL DEFAULT 0 CHECK (life_saving_missed >= 0),
    life_loans_on_plan       int         NOT NULL DEFAULT 0 CHECK (life_loans_on_plan >= 0),
    life_loans_off_plan      int         NOT NULL DEFAULT 0 CHECK (life_loans_off_plan >= 0),
    life_inst_on_time        int         NOT NULL DEFAULT 0 CHECK (life_inst_on_time >= 0),
    life_inst_late           int         NOT NULL DEFAULT 0 CHECK (life_inst_late >= 0),
    cur_saving_on_time       int         NOT NULL DEFAULT 0 CHECK (cur_saving_on_time >= 0),
    cur_saving_missed        int         NOT NULL DEFAULT 0 CHECK (cur_saving_missed >= 0),
    cur_loans_on_plan        int         NOT NULL DEFAULT 0 CHECK (cur_loans_on_plan >= 0),
    cur_loans_off_plan       int         NOT NULL DEFAULT 0 CHECK (cur_loans_off_plan >= 0),
    cur_inst_on_time         int         NOT NULL DEFAULT 0 CHECK (cur_inst_on_time >= 0),
    cur_inst_late            int         NOT NULL DEFAULT 0 CHECK (cur_inst_late >= 0),
    loans_disbursed          int         NOT NULL DEFAULT 0 CHECK (loans_disbursed >= 0),
    hardship_plans           int         NOT NULL DEFAULT 0 CHECK (hardship_plans >= 0),
    defaults                 int         NOT NULL DEFAULT 0 CHECK (defaults >= 0),
    last_saving_month        date,       -- first day of the last month with a deposit
    saving_accounted_through date,       -- first day of the last month counted, as saved or as missed
    missed_streak            int         NOT NULL DEFAULT 0 CHECK (missed_streak >= 0),
    open_late                text[]      NOT NULL DEFAULT '{}',  -- 'loanId:seq' of missed, still unpaid instalments
    settled                  text[]      NOT NULL DEFAULT '{}',  -- 'loanId:seq' of instalments already paid
    fact_count               bigint      NOT NULL DEFAULT 0,
    updated_at               timestamptz NOT NULL DEFAULT now()
);
-- The monthly close finds members whose last counted month is behind.
CREATE INDEX member_record_close_due ON creditrecord.member_record (saving_accounted_through)
    WHERE saving_accounted_through IS NOT NULL;

-- What a share link shows, frozen when the link is made. Nothing that identifies the member is copied here
-- beyond the owner key the share needs for revocation.
CREATE TABLE creditrecord.record_snapshot (
    id                   uuid        PRIMARY KEY,
    member_id            uuid        NOT NULL,
    level                smallint    NOT NULL CHECK (level BETWEEN 0 AND 4),
    record_since         date        NOT NULL,
    loans_on_plan        int         NOT NULL CHECK (loans_on_plan >= 0),
    on_time_band         text        NOT NULL CHECK (on_time_band IN ('DEVELOPING', 'FAIR', 'STRONG', 'EXCELLENT')),
    defaults_at_capture  int         NOT NULL CHECK (defaults_at_capture >= 0),
    captured_on          date        NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE creditrecord.record_share (
    id              uuid        PRIMARY KEY,
    member_id       uuid        NOT NULL,
    snapshot_id     uuid        NOT NULL REFERENCES creditrecord.record_snapshot (id),
    detail          text        NOT NULL CHECK (detail IN ('LEVEL', 'SUMMARY', 'FULL')),
    key_id          text        NOT NULL,
    idempotency_key text        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    fingerprint     text        NOT NULL,
    issued_at       timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    UNIQUE (member_id, idempotency_key),
    -- The service allows 30 days; the extra day is slack so a daylight-saving shift in a session time zone never trips it.
    CONSTRAINT share_expiry_window CHECK (expires_at > issued_at AND expires_at <= issued_at + interval '31 days')
);
CREATE INDEX record_share_by_member ON creditrecord.record_share (member_id, issued_at DESC);

CREATE FUNCTION creditrecord.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END $$;

CREATE TRIGGER record_fact_append_only BEFORE UPDATE OR DELETE ON creditrecord.record_fact
    FOR EACH ROW EXECUTE FUNCTION creditrecord.reject_mutation();
CREATE TRIGGER record_snapshot_append_only BEFORE UPDATE OR DELETE ON creditrecord.record_snapshot
    FOR EACH ROW EXECUTE FUNCTION creditrecord.reject_mutation();

-- A share can only ever be revoked, once. Its token, snapshot and expiry never change.
CREATE FUNCTION creditrecord.guard_share() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'creditrecord.record_share is append-only: DELETE is not allowed' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.revoked_at IS NOT NULL
       OR NEW.id <> OLD.id OR NEW.member_id <> OLD.member_id OR NEW.snapshot_id <> OLD.snapshot_id
       OR NEW.detail <> OLD.detail OR NEW.key_id <> OLD.key_id OR NEW.idempotency_key <> OLD.idempotency_key
       OR NEW.fingerprint <> OLD.fingerprint OR NEW.issued_at <> OLD.issued_at OR NEW.expires_at <> OLD.expires_at THEN
        RAISE EXCEPTION 'creditrecord.record_share may only be revoked, once' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER record_share_guard BEFORE UPDATE OR DELETE ON creditrecord.record_share
    FOR EACH ROW EXECUTE FUNCTION creditrecord.guard_share();
