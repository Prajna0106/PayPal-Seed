package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.agentgateway.api.AgentRuns;
import com.paypalseed.agentgateway.api.AgentTokens;
import com.paypalseed.agentgateway.api.KillSwitch;
import com.paypalseed.agentgateway.api.llm.ChatMessage;
import com.paypalseed.agentgateway.api.llm.ModelRequest;
import com.paypalseed.agentgateway.api.llm.ModelResponse;
import com.paypalseed.guardian.application.usecases.GuardianRunner;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.support.IntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The Guardian end to end with a scripted model (TC-AGT-*, TC-SEC-*): tools bound to one loan, the per-run
 * tool cap, a grounded narrative that passes the validator, an invented number that does not, and the kill
 * switch stopping the agent before any model call.
 */
@TestPropertySource(properties = {"seed.llm.models=primary/model-a", "seed.llm.daily-budget-micro-usd=5000000"})
class GuardianIT extends IntegrationTest {

    private static final Pattern BLOCK = Pattern.compile("<tool_result tool=\"([a-z_]+)\">\\n(.*?)\\n</tool_result>", Pattern.DOTALL);
    private static final UUID ADMIN = UUID.randomUUID();

    @Autowired
    AgentTokens tokens;
    @Autowired
    AgentRuns runs;
    @Autowired
    GuardianRunner guardian;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void agentsLive() throws Exception {
        killSwitch(KillSwitch.GLOBAL, "LIVE");
        killSwitch(GuardianRunner.AGENT, "LIVE");
        jdbc.sql("DELETE FROM agentgateway.usage_budget").update();
    }

    @Test
    void toolsAnswerOnlyForTheRunsOwnLoanAndNeverNameTheMember() throws Exception {
        UUID member = UUID.randomUUID();
        LoanSummary loan = disburse(member, 1200, 8, 20, LocalDate.parse("2027-01-05"));
        LoanSummary other = disburse(UUID.randomUUID(), 600, 6, 10, LocalDate.parse("2027-01-05"));
        String token = token(loan.loanId(), UUID.randomUUID());

        mvc.perform(get("/agent/v1/tools/loan-summary").param("loanId", other.loanId().toString()).header("Authorization", token))
                .andExpect(status().isNotFound());
        String body = mvc.perform(get("/agent/v1/tools/loan-summary").param("loanId", loan.loanId().toString())
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principalCents").value(120000))
                .andExpect(jsonPath("$.memberRef").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(member.toString());
        mvc.perform(get("/agent/v1/tools/loan-summary").param("loanId", loan.loanId().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theSeventhToolCallOfARunIsRefused() throws Exception {
        LoanSummary loan = disburse(UUID.randomUUID(), 1200, 8, 20, LocalDate.parse("2027-01-05"));
        String token = token(loan.loanId(), UUID.randomUUID());
        for (int i = 0; i < 6; i++) {
            mvc.perform(get("/agent/v1/tools/repayment-schedule").param("loanId", loan.loanId().toString())
                    .header("Authorization", token)).andExpect(status().isOk());
        }
        mvc.perform(get("/agent/v1/tools/repayment-schedule").param("loanId", loan.loanId().toString())
                        .header("Authorization", token))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/tool-call-limit")));
    }

    @Test
    void aGroundedNarrativeIsApprovableAndAnInventedNumberIsRejected() throws Exception {
        llm.answerWith((request, models) -> answer(request, models.get(0), false));
        UUID grounded = openCase();
        settle();
        assertRun(grounded, "APPROVABLE", null);

        llm.reset();
        llm.answerWith((request, models) -> answer(request, models.get(0), true));
        UUID invented = openCase();
        settle();
        assertRun(invented, "REJECTED", null);
        assertThat(failedChecks(invented)).anyMatch(code -> code.startsWith("V06"));
    }

    @Test
    void templatesOnlyStopsTheGuardianBeforeAnyModelCall() throws Exception {
        killSwitch(GuardianRunner.AGENT, "TEMPLATES_ONLY");

        UUID caseId = openCase();
        settle();

        assertThat(llm.calls).isEmpty();
        assertRun(caseId, "FAILED", "AGENT_DISABLED");
    }

    /** Ana's month 3: steady income, then a 52% drop, and her instalment due inside the scan window. */
    private UUID openCase() throws Exception {
        UUID member = UUID.randomUUID();
        LocalDate disbursed = LocalDate.parse("2027-02-25");
        LoanSummary loan = disburse(member, 1200, 8, 20, disbursed);
        LocalDate asOf = LocalDate.parse("2027-03-10");
        StringBuilder weeks = new StringBuilder();
        for (int i = 0; i < 13; i++) {
            LocalDate start = asOf.minusDays(7L * (13 - i));
            long cents = i < 9 ? 20000 : 9600;
            weeks.append(i == 0 ? "" : ",").append("{\"weekStart\":\"").append(start).append("\",\"inflowCents\":").append(cents).append('}');
        }
        mvc.perform(put("/internal/underwriting/members/" + member + "/income").header("Authorization", SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"consent\":true,\"weeks\":[" + weeks + "]}"))
                .andExpect(status().is2xxSuccessful());
        clock.set(asOf);
        String created = mvc.perform(post("/api/v1/hardship/cases").header("X-Seed-Member-Id", member.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"loanId\":\"" + loan.loanId() + "\"}"))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(created).path("caseId").asText());
    }

    private List<String> failedChecks(UUID caseId) {
        return jdbc.sql("""
                SELECT (c ->> 'code') || ':' || coalesce(c ->> 'detail', '') FROM governance.verdict v
                  JOIN governance.proposal p ON p.id = v.proposal_id,
                       jsonb_array_elements(v.checks) c
                 WHERE p.subject_id = :case AND (c ->> 'passed')::boolean = false
                """).param("case", caseId).query(String.class).list();
    }

    private void assertRun(UUID caseId, String verdict, String errorCode) {
        Map<String, Object> run = jdbc.sql("""
                SELECT verdict, error_code, prompt_hash, finished_at IS NOT NULL AS finished
                  FROM agentgateway.agent_run WHERE case_id = :case AND agent = :agent ORDER BY started_at DESC LIMIT 1
                """).param("case", caseId).param("agent", GuardianRunner.AGENT).query().singleRow();
        assertThat(run.get("finished")).isEqualTo(true);
        assertThat(run.get("verdict")).as("failed checks %s", failedChecks(caseId)).isEqualTo(verdict);
        assertThat(run.get("error_code")).isEqualTo(errorCode);
        assertThat((String) run.get("prompt_hash")).matches("sha256:[0-9a-f]{16}");
    }

    /**
     * A model that reads only what the tools returned: it says READY while gathering, keeps the level on a
     * second opinion, and writes the narrative from the schedule's next owed instalment.
     */
    private ModelResponse answer(ModelRequest request, String model, boolean invent) {
        if (request.jsonSchemaName() == null) {
            return llm.response(model, "READY", List.of());
        }
        Map<String, JsonNode> data = blocks(request.messages());
        if (request.jsonSchemaName().equals("guardian_second_opinion")) {
            String level = data.get("get_hardship_signals").path("computedLevel").asText();
            return llm.response(model, "{\"proposedLevel\":\"" + level + "\",\"escalated\":false,\"citedSignals\":[],\"reason\":\"keep\"}",
                    List.of());
        }
        JsonNode schedule = data.get("get_repayment_schedule");
        int index = 0;
        JsonNode next = null;
        for (JsonNode i : schedule.path("instalments")) {
            if (i.path("remainingCents").asLong() > 0) {
                next = i;
                break;
            }
            index++;
        }
        long cents = invent ? next.path("amountCents").asLong() + 2500 : next.path("amountCents").asLong();
        String dollars = "$" + cents / 100;
        String due = next.path("dueDate").asText();
        String narrative = """
                {"officerBrief":"The %s instalment due on %s is at risk. The options shown were built by the code.",
                 "memberMessage":{"en":"Your %s payment is due on %s. You can pick an option or keep your current plan.",
                                  "es":"Tu pago de %s vence el %s. Puedes mantener tu plan actual o ver las alternativas."},
                 "figures":[{"value":"%d","unit":"USD_CENTS","kind":"INSTALMENT","tool":"get_repayment_schedule","field":"instalments[%d].amountCents"},
                            {"value":"%s","unit":"DATE","kind":"DUE_DATE","tool":"get_repayment_schedule","field":"instalments[%d].dueDate"}]}
                """.formatted(dollars, due, dollars, due, dollars, due, cents, index, due, index);
        return llm.response(model, narrative, List.of());
    }

    private Map<String, JsonNode> blocks(List<ChatMessage> messages) {
        Map<String, JsonNode> found = new java.util.HashMap<>();
        for (ChatMessage m : messages) {
            Matcher matcher = BLOCK.matcher(m.content() == null ? "" : m.content());
            while (matcher.find()) {
                try {
                    found.put(matcher.group(1), json.readTree(matcher.group(2)));
                } catch (Exception e) {
                    throw new AssertionError("tool result is not JSON", e);
                }
            }
        }
        return found;
    }

    private String token(UUID loanId, UUID caseId) {
        UUID runId = runs.start(GuardianRunner.AGENT, caseId, loanId, "sha256:0000000000000000", "NARRATIVE");
        return "Bearer " + tokens.issue(runId, loanId, caseId, Set.of(AgentTokens.LOAN_READ, AgentTokens.SCHEDULE_READ),
                Duration.ofMinutes(5)).token();
    }

    private void killSwitch(String scope, String mode) throws Exception {
        mvc.perform(desk(post("/api/v1/desk/agents/kill-switch")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"" + scope + "\",\"mode\":\"" + mode + "\",\"reason\":\"test\"}"))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder desk(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Seed-Staff-Id", ADMIN.toString()).header("X-Seed-Staff-Roles", "ADMIN");
    }
}
