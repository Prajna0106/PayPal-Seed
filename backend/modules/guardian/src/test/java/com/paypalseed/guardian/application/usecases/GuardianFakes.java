package com.paypalseed.guardian.application.usecases;

import com.paypalseed.agentgateway.api.AgentPrincipal;
import com.paypalseed.agentgateway.api.AgentRuns;
import com.paypalseed.agentgateway.api.AgentTokens;
import com.paypalseed.agentgateway.api.AgentTools;
import com.paypalseed.agentgateway.api.KillSwitch;
import com.paypalseed.agentgateway.api.ModelGateway;
import com.paypalseed.agentgateway.api.ProposalIntake;
import com.paypalseed.agentgateway.api.llm.ModelRequest;
import com.paypalseed.agentgateway.api.llm.ModelResponse;
import com.paypalseed.agentgateway.api.llm.ToolCall;
import com.paypalseed.agentgateway.api.llm.ToolSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Hand-written fakes for the gateway ports the Guardian uses: each records what it was asked. */
final class GuardianFakes {

    static final UUID CASE = UUID.fromString("0197f3a8-1111-7000-8000-00000000c001");
    static final UUID LOAN = UUID.fromString("0197f3a2-5c1e-7b3a-9d10-3c4f1a2b9e01");

    private GuardianFakes() {
    }

    static final class Model implements ModelGateway {
        final Deque<Function<ModelRequest, ModelResponse>> script = new ArrayDeque<>();
        final List<ModelRequest> requests = new ArrayList<>();

        Model then(Function<ModelRequest, ModelResponse> step) {
            script.add(step);
            return this;
        }

        Model thenText(String content) {
            return then(r -> response(content, List.of()));
        }

        Model thenTools(ToolCall... calls) {
            return then(r -> response(null, List.of(calls)));
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            requests.add(request);
            if (script.isEmpty()) {
                throw new AssertionError("unexpected model call for " + request.purpose());
            }
            return script.poll().apply(request);
        }

        static ModelResponse response(String content, List<ToolCall> calls) {
            return new ModelResponse("test/model", content, calls, 100, 50, 10, 5, calls.isEmpty() ? "stop" : "tool_calls");
        }
    }

    static final class Tools implements AgentTools {
        final Map<String, String> answers = new HashMap<>();
        final List<String> called = new ArrayList<>();

        Tools answer(String tool, String json) {
            answers.put(tool, json);
            return this;
        }

        @Override
        public List<ToolSpec> catalogue() {
            return List.of(new ToolSpec(GET_LOAN_AGING, "aging", "{\"type\":\"object\"}"),
                    new ToolSpec(GET_INCOME_SUMMARY, "income", "{\"type\":\"object\"}"));
        }

        @Override
        public ToolResult call(String token, String toolName, String argumentsJson) {
            called.add(toolName);
            String json = answers.get(toolName);
            return json == null
                    ? new ToolResult(toolName, false, "{\"error\":\"not-found\",\"detail\":\"no such tool\"}", "not-found")
                    : new ToolResult(toolName, true, json, null);
        }
    }

    static final class Tokens implements AgentTokens {
        final List<UUID> issued = new ArrayList<>();
        final List<UUID> revoked = new ArrayList<>();

        @Override
        public IssuedToken issue(UUID runId, UUID loanId, UUID caseId, Set<String> scopes, Duration ttl) {
            issued.add(runId);
            return new IssuedToken("token-" + runId, Instant.parse("2027-03-10T17:00:00Z"));
        }

        @Override
        public Optional<AgentPrincipal> verify(String token) {
            return Optional.empty();
        }

        @Override
        public void revoke(UUID runId) {
            revoked.add(runId);
        }
    }

    static final class Runs implements AgentRuns {
        final UUID runId = UUID.fromString("0197f3a9-0000-7000-8000-00000000a001");
        int calls;
        int runsToday;

        @Override
        public UUID start(String agent, UUID caseId, UUID loanId, String promptHash, String mode) {
            return runId;
        }

        @Override
        public void recordCall(UUID id, ModelResponse response) {
            calls++;
        }

        @Override
        public int toolCallCount(UUID id) {
            return 0;
        }

        @Override
        public int recordToolCall(UUID id, String tool, long latencyMs, String argsHash) {
            return 0;
        }

        @Override
        public void finish(UUID id, String groundingResult, String verdict, String errorCode) {
        }

        @Override
        public String agentOf(UUID id) {
            return "guardian";
        }

        @Override
        public int runsForCaseSince(UUID caseId, Instant since) {
            return runsToday;
        }
    }

    static final class Switch implements KillSwitch {
        Mode mode = Mode.LIVE;

        @Override
        public Mode mode(String agent) {
            return mode;
        }

        @Override
        public State state(String agent) {
            return new State(mode, Instant.EPOCH, null, null, Map.of());
        }

        @Override
        public void set(String scope, Mode mode, String reason, String changedBy) {
            this.mode = mode;
        }

        @Override
        public void recordValidatorOutcome(String agent, boolean rejected) {
        }

        @Override
        public void recordGroundingFailure(String agent) {
        }
    }

    static final class Intake implements ProposalIntake {
        final List<String> submitted = new ArrayList<>();
        final List<String> failures = new ArrayList<>();

        @Override
        public Receipt submit(String token, String idempotencyKey, String proposalJson) {
            submitted.add(proposalJson);
            return new Receipt(UUID.randomUUID(), "QUEUED_FOR_MEMBER", "APPROVABLE", 12, List.of(), false, false);
        }

        @Override
        public void reportFailure(UUID caseId, UUID runId, String errorCode) {
            failures.add(errorCode);
        }
    }
}
