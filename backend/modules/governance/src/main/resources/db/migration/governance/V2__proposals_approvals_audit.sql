-- F7 minimal slice for F5: proposal -> verdict -> approval (maker-checker) and the hash-chained audit log.
-- Design: f5-hardship-safety-net/02-design.md §3.2 and §5.2, ADR 0003, ADR 0009.
-- Governance is generic: it knows proposal kinds and subjects, never what a hardship case is.

-- An agent's (or a service's) proposal. Untrusted input: only the owner module's validator decides.
CREATE TABLE governance.proposal (
    id              uuid        PRIMARY KEY,
    kind            text        NOT NULL CHECK (kind ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    subject_id      uuid        NOT NULL,
    payload         jsonb       NOT NULL,
    payload_hash    text        NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),  -- SHA-256 of the canonical JSON
    submitted_by    text        NOT NULL,
    idempotency_key text        NOT NULL UNIQUE,  -- same key and hash replays; same key, other hash is refused
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX proposal_by_subject ON governance.proposal (subject_id, created_at);

CREATE TABLE governance.verdict (
    proposal_id       uuid        PRIMARY KEY REFERENCES governance.proposal (id),
    result            text        NOT NULL CHECK (result IN ('APPROVABLE', 'REJECTED')),
    checks            jsonb       NOT NULL,  -- every check with pass or fail and the values compared
    validator_version text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now()
);

-- A decision a human (or the policy, for AUTO_POLICY) must take about one subject.
CREATE TABLE governance.approval_request (
    id                uuid        PRIMARY KEY,
    subject_id        uuid        NOT NULL,
    subject_member_id uuid,                    -- the member the decision is about: they may never decide it
    tier              text        NOT NULL CHECK (tier IN ('AUTO_POLICY', 'MAKER_CHECKER')),
    offer_id          uuid,
    snapshot_hash     text        NOT NULL,    -- what the member saw; every decision re-checks it (HINV-14)
    checker_required  boolean     NOT NULL,
    status            text        NOT NULL CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED', 'EXPIRED', 'VOIDED')),
    sla_due_at        timestamptz,
    opened_at         timestamptz NOT NULL DEFAULT now(),
    closed_at         timestamptz,
    closed_reason     text,
    CONSTRAINT approval_request_closed_state CHECK ((status = 'OPEN') = (closed_at IS NULL)),
    CONSTRAINT approval_request_policy_tier_has_no_checker CHECK (tier <> 'AUTO_POLICY' OR NOT checker_required)
);
CREATE UNIQUE INDEX approval_request_one_open_per_subject ON governance.approval_request (subject_id) WHERE status = 'OPEN';

-- One human decision. The maker decides first; the checker countersigns a maker approval.
CREATE TABLE governance.approval (
    id                 uuid        PRIMARY KEY,
    request_id         uuid        NOT NULL REFERENCES governance.approval_request (id),
    role               text        NOT NULL CHECK (role IN ('MAKER', 'CHECKER')),
    staff_id           uuid        NOT NULL,
    staff_member_id    uuid,                   -- set only when the staff user is also a member
    decision           text        NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    reason_code        text        CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    note               text        CHECK (length(note) <= 2000),
    evidence_viewed_ms bigint      NOT NULL CHECK (evidence_viewed_ms >= 0),
    idempotency_key    text        NOT NULL,
    decided_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (request_id, role),
    CONSTRAINT approval_reject_has_reason CHECK (decision <> 'REJECT' OR reason_code IS NOT NULL)
);
CREATE INDEX approval_by_staff ON governance.approval (staff_id, decided_at DESC);

-- Separation of duties, enforced by the database even if the service is bypassed. Deferred to commit so
-- the request and its decisions can be written in any order inside one transaction.
CREATE FUNCTION governance.assert_separation_of_duties() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_subject_member uuid;
    v_maker_staff    uuid;
    v_maker_member   uuid;
    v_maker_decision text;
BEGIN
    SELECT subject_member_id INTO v_subject_member FROM governance.approval_request WHERE id = NEW.request_id;
    IF v_subject_member IS NOT NULL
       AND (NEW.staff_id = v_subject_member OR NEW.staff_member_id = v_subject_member) THEN
        RAISE EXCEPTION 'separation of duties: the decider is the member the request is about'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.role = 'CHECKER' THEN
        SELECT staff_id, staff_member_id, decision INTO v_maker_staff, v_maker_member, v_maker_decision
          FROM governance.approval WHERE request_id = NEW.request_id AND role = 'MAKER';
        IF NOT FOUND OR v_maker_decision <> 'APPROVE' THEN
            RAISE EXCEPTION 'separation of duties: a checker decision needs a maker approval first'
                USING ERRCODE = 'check_violation';
        END IF;
        IF v_maker_staff = NEW.staff_id
           OR (v_maker_member IS NOT NULL AND v_maker_member = NEW.staff_member_id) THEN
            RAISE EXCEPTION 'separation of duties: the checker is the maker'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER approval_separation_of_duties
    AFTER INSERT ON governance.approval DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION governance.assert_separation_of_duties();

-- Hash-chained audit log: hash = SHA-256(prev_hash || canonical JSON of the entry), computed by the
-- application. Writers take the chain lock before reading the last hash; the trigger re-checks under the
-- same lock, so two concurrent writers can never fork the chain.
CREATE TABLE governance.audit_entry (
    seq        bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_type text        NOT NULL,
    subject_id uuid,
    actor_type text        NOT NULL,
    actor_id   text        NOT NULL,
    payload    jsonb       NOT NULL,
    prev_hash  bytea       NOT NULL CHECK (length(prev_hash) = 32),
    hash       bytea       NOT NULL UNIQUE CHECK (length(hash) = 32),
    created_at timestamptz NOT NULL
);
CREATE INDEX audit_entry_by_subject ON governance.audit_entry (subject_id, seq);

CREATE FUNCTION governance.audit_chain_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_last bytea;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('governance.audit_entry'));
    SELECT hash INTO v_last FROM governance.audit_entry ORDER BY seq DESC LIMIT 1;
    IF NEW.prev_hash IS DISTINCT FROM COALESCE(v_last, decode(repeat('00', 32), 'hex')) THEN
        RAISE EXCEPTION 'audit chain: prev_hash does not match the last entry'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER audit_entry_chain BEFORE INSERT ON governance.audit_entry
    FOR EACH ROW EXECUTE FUNCTION governance.audit_chain_guard();

-- Append-only, enforced by the database rather than by convention.
CREATE FUNCTION governance.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END $$;

CREATE TRIGGER proposal_append_only BEFORE UPDATE OR DELETE ON governance.proposal
    FOR EACH ROW EXECUTE FUNCTION governance.reject_mutation();
CREATE TRIGGER verdict_append_only BEFORE UPDATE OR DELETE ON governance.verdict
    FOR EACH ROW EXECUTE FUNCTION governance.reject_mutation();
CREATE TRIGGER approval_append_only BEFORE UPDATE OR DELETE ON governance.approval
    FOR EACH ROW EXECUTE FUNCTION governance.reject_mutation();
CREATE TRIGGER audit_entry_append_only BEFORE UPDATE OR DELETE ON governance.audit_entry
    FOR EACH ROW EXECUTE FUNCTION governance.reject_mutation();
CREATE TRIGGER audit_entry_no_truncate BEFORE TRUNCATE ON governance.audit_entry
    FOR EACH STATEMENT EXECUTE FUNCTION governance.reject_mutation();
