-- F5 agent runtime: run trace, scoped run tokens, kill switch and the daily model budget
-- (04-agent-architecture §3, §10, §12). Rows hold hashes and numbers, never prompt or member text.

CREATE TABLE agentgateway.agent_run (
    id               uuid        PRIMARY KEY,
    agent            text        NOT NULL,
    mode             text        NOT NULL CHECK (mode IN ('SECOND_OPINION', 'BRIEF', 'OUTREACH', 'NARRATIVE', 'EXTRACT')),
    kill_switch_mode text        NOT NULL CHECK (kill_switch_mode IN ('LIVE', 'SHADOW', 'TEMPLATES_ONLY')),
    case_id          uuid,
    loan_id          uuid,
    prompt_hash      text        NOT NULL,
    model            text,                     -- the model that served the last call
    calls            int         NOT NULL DEFAULT 0,
    tokens_in        int         NOT NULL DEFAULT 0,
    tokens_out       int         NOT NULL DEFAULT 0,
    cost_micro_usd   bigint      NOT NULL DEFAULT 0,
    latency_ms       bigint      NOT NULL DEFAULT 0,
    grounding_result text        CHECK (grounding_result IN ('PASSED', 'FAILED_FELL_BACK', 'NOT_RUN')),
    verdict          text,
    error_code       text,
    started_at       timestamptz NOT NULL DEFAULT now(),
    finished_at      timestamptz
);
CREATE INDEX agent_run_by_case ON agentgateway.agent_run (case_id, started_at);

-- A finished run is the audit record of what the model did: it can no longer change.
CREATE FUNCTION agentgateway.reject_finished_run_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.finished_at IS NOT NULL THEN
        RAISE EXCEPTION 'agent run % is finished and append-only', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_run_append_only_when_finished BEFORE UPDATE OR DELETE ON agentgateway.agent_run
    FOR EACH ROW EXECUTE FUNCTION agentgateway.reject_finished_run_change();

CREATE TABLE agentgateway.agent_tool_call (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id     uuid        NOT NULL REFERENCES agentgateway.agent_run (id),
    tool       text        NOT NULL,
    args_hash  text        NOT NULL,
    latency_ms bigint      NOT NULL CHECK (latency_ms >= 0),
    called_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX agent_tool_call_by_run ON agentgateway.agent_tool_call (run_id);

-- The bearer token itself is never stored: jti is the SHA-256 of it, so a database leak yields no usable token.
CREATE TABLE agentgateway.agent_token (
    jti        text        PRIMARY KEY,
    run_id     uuid        NOT NULL,
    loan_id    uuid        NOT NULL,
    case_id    uuid        NOT NULL,
    scopes     text[]      NOT NULL,
    issued_at  timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT agent_token_short_lived CHECK (expires_at > issued_at AND expires_at <= issued_at + interval '10 minutes')
);
CREATE INDEX agent_token_by_run ON agentgateway.agent_token (run_id);

-- Current mode per scope ('GLOBAL' or an agent name); the most restrictive applicable row wins.
CREATE TABLE agentgateway.kill_switch (
    scope      text        PRIMARY KEY,
    mode       text        NOT NULL CHECK (mode IN ('LIVE', 'SHADOW', 'TEMPLATES_ONLY')),
    reason     text,
    tripped_by text        NOT NULL CHECK (tripped_by IN ('OFFICER', 'AUTO_VALIDATOR_REJECTIONS',
                               'AUTO_GROUNDING_FAILURES', 'AUTO_BUDGET', 'AUTO_INJECTION_CANARY')),
    changed_by text        NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE agentgateway.kill_switch_event (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope      text        NOT NULL,
    from_mode  text        NOT NULL,
    to_mode    text        NOT NULL,
    reason     text,
    tripped_by text        NOT NULL,
    changed_by text        NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE agentgateway.usage_budget (
    day            date   PRIMARY KEY,
    calls          int    NOT NULL DEFAULT 0 CHECK (calls >= 0),
    tokens_used    bigint NOT NULL DEFAULT 0 CHECK (tokens_used >= 0),
    cost_micro_usd bigint NOT NULL DEFAULT 0 CHECK (cost_micro_usd >= 0)
);

CREATE FUNCTION agentgateway.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '%.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END $$;
CREATE TRIGGER agent_tool_call_append_only BEFORE UPDATE OR DELETE ON agentgateway.agent_tool_call
    FOR EACH ROW EXECUTE FUNCTION agentgateway.reject_mutation();
CREATE TRIGGER kill_switch_event_append_only BEFORE UPDATE OR DELETE ON agentgateway.kill_switch_event
    FOR EACH ROW EXECUTE FUNCTION agentgateway.reject_mutation();
