package com.paypalseed.agentgateway.infrastructure.persistence;

import static com.paypalseed.agentgateway.infrastructure.persistence.jooq.Tables.AGENT_RUN;
import static com.paypalseed.agentgateway.infrastructure.persistence.jooq.Tables.AGENT_TOOL_CALL;

import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class AgentRunRepository {

    private final DSLContext dsl;

    public AgentRunRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(UUID id, String agent, String mode, String killSwitchMode, UUID caseId, UUID loanId, String promptHash) {
        dsl.insertInto(AGENT_RUN)
                .set(AGENT_RUN.ID, id)
                .set(AGENT_RUN.AGENT, agent)
                .set(AGENT_RUN.MODE, mode)
                .set(AGENT_RUN.KILL_SWITCH_MODE, killSwitchMode)
                .set(AGENT_RUN.CASE_ID, caseId)
                .set(AGENT_RUN.LOAN_ID, loanId)
                .set(AGENT_RUN.PROMPT_HASH, promptHash)
                .execute();
    }

    public void addCall(UUID id, String model, int tokensIn, int tokensOut, long costMicroUsd, long latencyMs) {
        dsl.update(AGENT_RUN)
                .set(AGENT_RUN.MODEL, model)
                .set(AGENT_RUN.CALLS, AGENT_RUN.CALLS.plus(1))
                .set(AGENT_RUN.TOKENS_IN, AGENT_RUN.TOKENS_IN.plus(tokensIn))
                .set(AGENT_RUN.TOKENS_OUT, AGENT_RUN.TOKENS_OUT.plus(tokensOut))
                .set(AGENT_RUN.COST_MICRO_USD, AGENT_RUN.COST_MICRO_USD.plus(costMicroUsd))
                .set(AGENT_RUN.LATENCY_MS, AGENT_RUN.LATENCY_MS.plus(latencyMs))
                .where(AGENT_RUN.ID.eq(id))
                .execute();
    }

    public int addToolCall(UUID runId, String tool, long latencyMs, String argsHash) {
        dsl.insertInto(AGENT_TOOL_CALL)
                .set(AGENT_TOOL_CALL.RUN_ID, runId)
                .set(AGENT_TOOL_CALL.TOOL, tool)
                .set(AGENT_TOOL_CALL.LATENCY_MS, latencyMs)
                .set(AGENT_TOOL_CALL.ARGS_HASH, argsHash)
                .execute();
        return dsl.fetchCount(AGENT_TOOL_CALL, AGENT_TOOL_CALL.RUN_ID.eq(runId));
    }

    public int toolCallCount(UUID runId) {
        return dsl.fetchCount(AGENT_TOOL_CALL, AGENT_TOOL_CALL.RUN_ID.eq(runId));
    }

    /** Returns false when the run was already finished (a finished run never changes). */
    public boolean finish(UUID id, String groundingResult, String verdict, String errorCode) {
        return dsl.update(AGENT_RUN)
                .set(AGENT_RUN.GROUNDING_RESULT, groundingResult)
                .set(AGENT_RUN.VERDICT, verdict)
                .set(AGENT_RUN.ERROR_CODE, errorCode)
                .set(AGENT_RUN.FINISHED_AT, PgSql.now())
                .where(AGENT_RUN.ID.eq(id).and(AGENT_RUN.FINISHED_AT.isNull()))
                .execute() == 1;
    }

    public Optional<String> agentOf(UUID id) {
        return dsl.select(AGENT_RUN.AGENT).from(AGENT_RUN).where(AGENT_RUN.ID.eq(id)).fetchOptional(AGENT_RUN.AGENT);
    }

    public int runsForCaseSince(UUID caseId, Instant since) {
        return dsl.fetchCount(AGENT_RUN, AGENT_RUN.CASE_ID.eq(caseId).and(AGENT_RUN.STARTED_AT.ge(since)));
    }
}
