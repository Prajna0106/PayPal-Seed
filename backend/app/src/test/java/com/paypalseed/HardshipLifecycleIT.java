package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.engagement.infrastructure.clients.ZapierSmsClient;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository;
import com.paypalseed.hardship.application.usecases.ApprovalFlow;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.support.PayPalWebhooks;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * F5 end to end with the AI unavailable, so every word is a template (E2E-H-02): the scan opens a case for an
 * Ana-like member, the offer goes out by SMS, the member answers by text, two officers decide, and lending applies
 * exactly what was chosen. Plus the member's other paths: decline, a stale offer, option A, and another member.
 */
@TestPropertySource(properties = "seed.hardship.agent-deadline=PT0S")
class HardshipLifecycleIT extends IntegrationTest {

    private static final LocalDate DISBURSED = LocalDate.of(2026, 11, 10);

    @Autowired
    ReminderRepository contacts;
    @Autowired
    ZapierSmsClient zapier;
    @Autowired
    ApprovalFlow approvals;
    @Autowired
    ObjectMapper json;

    private record Opened(UUID memberId, UUID loanId, UUID caseId, LocalDate scanDate, String phone) {
    }

    @Test
    void anaPausesBySmsAndTwoOfficersApprove() throws Exception {
        Opened ana = openAnaCase("+15550001001");

        assertThat(caseStatus(ana.caseId())).isEqualTo("OUTREACH_SENT");
        assertThat(jdbc.sql("SELECT narrative_source FROM hardship.hardship_case WHERE id = ?").param(ana.caseId())
                .query(String.class).single()).isEqualTo("TEMPLATE");
        List<ReminderRepository.Reminder> sent = contacts.forLoan(ana.loanId()).stream()
                .filter(r -> r.type().equals("HARDSHIP_OFFER") && r.channel().equals("SMS")).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).body()).startsWith("Seed:").contains("Reply ").contains("STOP").contains("B: pause");

        // The member replies B by text; replaying the same message changes nothing.
        sms(ana.phone(), "B", "msg-ana-1");
        sms(ana.phone(), "B", "msg-ana-1");
        settle();
        assertThat(caseStatus(ana.caseId())).isEqualTo("PENDING_APPROVAL");

        UUID maker = UUID.randomUUID();
        UUID checker = UUID.randomUUID();
        decide(ana.caseId(), maker, "OFFICER", "APPROVE", "maker-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MAKER")).andExpect(jsonPath("$.next").value("CHECKER"));
        decide(ana.caseId(), maker, "OFFICER", "APPROVE", "maker-1").andExpect(status().isOk()); // idempotent replay
        decide(ana.caseId(), maker, "OFFICER,CHECKER", "APPROVE", "checker-same").andExpect(status().isForbidden());
        decide(ana.caseId(), checker, "CHECKER", "APPROVE", "checker-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("APPROVED"));
        settle();

        assertThat(caseStatus(ana.caseId())).isEqualTo("APPLIED");
        ScheduleView schedule = lending.currentSchedule(ana.loanId());
        assertThat(schedule.version()).isEqualTo(2);
        assertThat(schedule.reason()).isEqualTo("HARDSHIP_PAUSE");
        assertThat(schedule.instalments().stream().mapToLong(i -> i.amountCents()).sum()).isEqualTo(120_000);

        approvals.execute(ana.caseId()); // a replayed saga is a no-op
        assertThat(lending.currentSchedule(ana.loanId()).version()).isEqualTo(2);
        assertThat(contacts.forLoan(ana.loanId())).anyMatch(r -> r.type().equals("HARDSHIP_UPDATE"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lending.loan_hardship_flag WHERE loan_id = ?").param(ana.loanId())
                .query(Integer.class).single()).isZero();
        assertIntegrity(ana.loanId());
    }

    @Test
    void memberDeclinesInTheApp() throws Exception {
        Opened m = openAnaCase("+15550001002");
        String offer = asMember(get("/api/v1/loans/{id}/hardship", m.loanId()), m.memberId());
        assertThat(json.readTree(offer).path("status").asText()).isEqualTo("OFFER_VIEWED");

        mvc.perform(withMember(post("/api/v1/hardship/cases/{id}/decline", m.caseId()), m.memberId())
                        .header("Idempotency-Key", "decline-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED_BY_MEMBER"));
        mvc.perform(withMember(get("/api/v1/loans/{id}/hardship", m.loanId()), m.memberId()))
                .andExpect(status().isNotFound());
        assertThat(lending.currentSchedule(m.loanId()).version()).isEqualTo(1);
    }

    @Test
    void aPaymentAfterTheOfferMakesItStale() throws Exception {
        Opened m = openAnaCase("+15550001003");
        JsonNode offer = json.readTree(asMember(get("/api/v1/loans/{id}/hardship", m.loanId()), m.memberId())).path("offer");

        webhook(PayPalWebhooks.captureCompleted(PayPalWebhooks.newEventId(), "CAP-H-STALE", m.loanId(), 5_000));
        settle();

        mvc.perform(withMember(post("/api/v1/hardship/cases/{id}/choice", m.caseId()), m.memberId())
                        .header("Idempotency-Key", "stale-1").contentType(MediaType.APPLICATION_JSON)
                        .content(choice(offer, "B")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://problems.paypal-seed.dev/hardship-offer-stale"));
        JsonNode revised = json.readTree(asMember(get("/api/v1/loans/{id}/hardship", m.loanId()), m.memberId())).path("offer");
        assertThat(revised.path("revision").asInt()).isEqualTo(2);
        mvc.perform(withMember(post("/api/v1/hardship/cases/{id}/choice", m.caseId()), m.memberId())
                        .header("Idempotency-Key", "stale-2").contentType(MediaType.APPLICATION_JSON)
                        .content(choice(revised, "B")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    void optionAIsApprovedByPolicyAndApplied() throws Exception {
        Opened m = openAnaCase("+15550001004");
        JsonNode offer = json.readTree(asMember(get("/api/v1/loans/{id}/hardship", m.loanId()), m.memberId())).path("offer");
        mvc.perform(withMember(post("/api/v1/hardship/cases/{id}/choice", m.caseId()), m.memberId())
                        .header("Idempotency-Key", "a-1").contentType(MediaType.APPLICATION_JSON).content(choice(offer, "A")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tier").value("AUTO_POLICY"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        settle();
        assertThat(caseStatus(m.caseId())).isEqualTo("APPLIED");
        assertThat(lending.currentSchedule(m.loanId()).reason()).isEqualTo("HARDSHIP_MOVE_DATE");
    }

    @Test
    void anotherMemberSeesNothing() throws Exception {
        Opened m = openAnaCase("+15550001005");
        UUID stranger = UUID.randomUUID();
        mvc.perform(withMember(get("/api/v1/loans/{id}/hardship", m.loanId()), stranger)).andExpect(status().isNotFound());
        JsonNode offer = json.readTree(asMember(get("/api/v1/loans/{id}/hardship", m.loanId()), m.memberId())).path("offer");
        mvc.perform(withMember(post("/api/v1/hardship/cases/{id}/choice", m.caseId()), stranger)
                        .header("Idempotency-Key", "x-1").contentType(MediaType.APPLICATION_JSON).content(choice(offer, "B")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/desk/hardship/cases").header("X-Seed-Member-Id", stranger.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(staff(get("/api/v1/desk/hardship/cases"), UUID.randomUUID(), "OFFICER").param("status", "NOT_A_STATUS"))
                .andExpect(status().isUnprocessableEntity());
    }

    /** Loan of $1,200 over 8 months; income 9 weeks at $200 then 4 weeks at $96: a persistent 52% drop. */
    private Opened openAnaCase(String phone) throws Exception {
        UUID member = UUID.randomUUID();
        LoanSummary loan = disburse(member, 1_200, 8, 20, DISBURSED);
        contacts.upsertContact(member, phone, "en", "America/Los_Angeles");
        LocalDate due = lending.currentSchedule(loan.loanId()).instalments().get(0).dueDate();
        LocalDate scanDate = due.minusDays(8);
        clock.set(scanDate);
        List<String> weeks = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            weeks.add("{\"weekStart\":\"" + scanDate.minusDays(91 - 7L * i) + "\",\"inflowCents\":" + (i < 9 ? 20_000 : 9_600) + "}");
        }
        mvc.perform(put("/internal/underwriting/members/{id}/income", member).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", SERVICE_TOKEN)
                        .content("{\"consent\":true,\"weeks\":[" + String.join(",", weeks) + "]}"))
                .andExpect(status().isOk());

        mvc.perform(post("/internal/jobs/hardship/scan").header("Authorization", SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"" + scanDate + "\",\"shardGroup\":0,\"shardCount\":1,\"limit\":500}"))
                .andExpect(status().isOk());
        UUID caseId = jdbc.sql("SELECT id FROM hardship.hardship_case WHERE loan_id = ?").param(loan.loanId())
                .query(UUID.class).single();
        assertThat(jdbc.sql("SELECT risk_level FROM hardship.hardship_case WHERE id = ?").param(caseId).query(String.class)
                .single()).isEqualTo("LIKELY_MISS");
        settle();
        mvc.perform(post("/internal/jobs/hardship/sweep").header("Authorization", SERVICE_TOKEN)).andExpect(status().isOk());
        settle();
        return new Opened(member, loan.loanId(), caseId, scanDate, phone);
    }

    private String caseStatus(UUID caseId) {
        return jdbc.sql("SELECT status FROM hardship.hardship_case WHERE id = ?").param(caseId).query(String.class).single();
    }

    private void sms(String from, String text, String messageId) throws Exception {
        String body = "{\"from\":\"" + from + "\",\"text\":\"" + text + "\",\"messageId\":\"" + messageId + "\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        mvc.perform(post("/api/v1/webhooks/zapier/sms").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Seed-Timestamp", ts).header("X-Seed-Signature", zapier.sign(ts + "." + body)))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions decide(UUID caseId, UUID staffId, String roles, String decision,
                                                                       String key) throws Exception {
        return mvc.perform(staff(post("/api/v1/desk/hardship/cases/{id}/decision", caseId), staffId, roles)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"" + decision + "\",\"reasonCode\":\"INCOME_DROP_VERIFIED\",\"evidenceViewedMs\":30000}"));
    }

    private static String choice(JsonNode offer, String label) {
        for (JsonNode o : offer.path("options")) {
            if (o.path("label").asText().equals(label)) {
                return "{\"offerId\":\"" + offer.path("offerId").asText() + "\",\"optionId\":\"" + o.path("optionId").asText() + "\"}";
            }
        }
        throw new AssertionError("option " + label + " not offered: " + offer);
    }

    private String asMember(MockHttpServletRequestBuilder request, UUID memberId) throws Exception {
        MvcResult r = mvc.perform(withMember(request, memberId)).andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder withMember(MockHttpServletRequestBuilder request, UUID memberId) {
        return request.header("X-Seed-Member-Id", memberId.toString());
    }

    private static MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder request, UUID staffId, String roles) {
        return request.header("X-Seed-Staff-Id", staffId.toString()).header("X-Seed-Staff-Roles", roles);
    }
}
