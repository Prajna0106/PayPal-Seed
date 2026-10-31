-- F5 support: the facts hardship pushes into lending (02-design.md §1.1, 03-algorithms.md §9).
-- Lending owns the data, so close of business reads only its own tables and never calls hardship.

-- A grace hold stops one fact (InstalmentMissed) from being written while a human decides.
-- Rows are never deleted: a release or an extension is recorded, not erased.
CREATE TABLE lending.loan_hold (
    id              uuid        PRIMARY KEY,
    loan_id         uuid        NOT NULL REFERENCES lending.loan (id),
    version         int         NOT NULL,
    seq             smallint    NOT NULL,
    due_date        date        NOT NULL,
    placed_on       date        NOT NULL,
    until           date        NOT NULL,
    cause           text        NOT NULL CHECK (cause IN ('CHOSEN_OPTION_PENDING', 'SLA_ESCALATION')),
    case_ref        text        NOT NULL,
    released_on     date,
    released_reason text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    -- the miss date is due + 6; a hold covers at most 3 COB dates from it, plus one audited 2-day extension
    CONSTRAINT loan_hold_bounded CHECK (until >= placed_on AND until <= due_date + 10),
    CONSTRAINT loan_hold_release_has_reason CHECK ((released_on IS NULL) = (released_reason IS NULL))
);
CREATE UNIQUE INDEX loan_hold_one_active ON lending.loan_hold (loan_id, seq) WHERE released_on IS NULL;
CREATE INDEX loan_hold_by_case ON lending.loan_hold (case_ref);

CREATE FUNCTION lending.reject_hold_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'lending.loan_hold rows are never deleted' USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER loan_hold_no_delete BEFORE DELETE ON lending.loan_hold
    FOR EACH ROW EXECUTE FUNCTION lending.reject_hold_delete();

-- Present while a hardship case is open: no automatic retry is planned and no grace nudge is sent (F4 AC3).
CREATE TABLE lending.loan_hardship_flag (
    loan_id  uuid        PRIMARY KEY REFERENCES lending.loan (id),
    case_ref text        NOT NULL,
    since    date        NOT NULL
);

-- Whose miss it was once a hold ended: SLA_BREACH when our own decision was late (F6 does not count it
-- against the member). NULL when no hold was involved, which is every F4 miss.
ALTER TABLE lending.instalment_missed
    ADD COLUMN attribution text CHECK (attribution IN ('MEMBER', 'SLA_BREACH'));

-- An approval is applied once: a replayed approvalRef finds its version instead of adding another.
CREATE UNIQUE INDEX schedule_version_one_per_approval ON lending.schedule_version (loan_id, caused_by)
    WHERE reason <> 'ORIGINAL';
