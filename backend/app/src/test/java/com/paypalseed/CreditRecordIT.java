package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.creditrecord.api.CreditRecordFacade;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.IngestOutcome;
import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.creditrecord.api.events.RecordLevelChanged;
import com.paypalseed.creditrecord.domain.token.ShareClaims;
import com.paypalseed.creditrecord.domain.token.ShareTokenCodec;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.events.LendingEvents.InstalmentMissed;
import com.paypalseed.lending.application.usecases.RepaymentService;
import com.paypalseed.lending.application.usecases.RepaymentService.Receipt;
import com.paypalseed.lending.domain.model.LoanTransaction.Source;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F6 end to end (ADR 0014): the record is built only from the events savings, lending and hardship really publish,
 * levels move when thresholds are met and only a default lowers one, a hardship plan costs nothing, and a share link
 * can be created, verified by a partner without signing in, revoked, and never leaks a person.
 */
@TestPropertySource(properties = {"seed.record.signing-secret=" + CreditRecordIT.SECRET, "seed.hardship.agent-deadline=PT0S"})
class CreditRecordIT extends IntegrationTest {

    static final String SECRET = "it-record-signing-secret-0123456789abcdef";
    private static final String MEMBER = "X-Seed-Member-Id";
    private static final Set<String> PARTNER_FIELDS = Set.of("valid", "level", "since", "loansRepaidOnPlan", "onTimeBand",
            "issuedAt", "expiresAt");

    /** Collects the level changes the record publishes. */
    static class LevelEvents {
        final List<RecordLevelChanged> seen = new CopyOnWriteArrayList<>();

        @EventListener
        void on(RecordLevelChanged e) {
            seen.add(e);
        }

        List<String> moves(UUID member) {
            return seen.stream().filter(e -> e.memberId().equals(member))
                    .map(e -> e.fromLevel() + ">" + e.toLevel() + "@" + e.cause()).toList();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Capture {
        @Bean
        LevelEvents levelEvents() {
            return new LevelEvents();
        }
    }

    @Autowired
    ObjectMapper json;
    @Autowired
    LevelEvents levelEvents;
    @Autowired
    CreditRecordFacade creditRecord;
    @Autowired
    CreateGoalUseCase createGoal;
    @Autowired
    RecordDepositUseCase deposits;
    @Autowired
    RepaymentService repayments;
    @Autowired
    ReminderRepository contacts;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ApplicationEventPublisher publisher;

    private final ShareTokenCodec itCodec = ShareTokenCodec.of("k1", Map.of("k1", SECRET));

    // ---- the path through real events ---------------------------------------------------------------------------

    @Test
    void savingAndRepayingARealLoanRaisesTheLevelOnlyThroughTheEventsTheModulesPublish() throws Exception {
        UUID ana = UUID.randomUUID();
        UUID goal = goalFor(ana);
        saveOn(ana, goal, LocalDate.of(2026, 11, 10), "c1");
        LoanSummary loan = disburse(ana, 300, 3, 9, LocalDate.of(2026, 11, 10));
        settle();
        assertThat(recordOf(ana).path("loans").path("disbursed").asInt()).isEqualTo(1);
        assertThat(recordOf(ana).path("level").asText()).isEqualTo("NEW");

        pay(loan, LocalDate.of(2026, 12, 9), "T1");
        saveOn(ana, goal, LocalDate.of(2026, 12, 10), "c2");
        pay(loan, LocalDate.of(2027, 1, 9), "T2");
        saveOn(ana, goal, LocalDate.of(2027, 1, 10), "c3");
        assertThat(recordOf(ana).path("level").asText()).isEqualTo("BUILDING");
        assertThat(recordOf(ana).path("loanLimitCents").asLong()).isEqualTo(150_000);

        pay(loan, LocalDate.of(2027, 2, 9), "T3");                 // the loan is repaid, with no recorded miss
        saveOn(ana, goal, LocalDate.of(2027, 2, 10), "c4");
        saveOn(ana, goal, LocalDate.of(2027, 3, 10), "c5");
        saveOn(ana, goal, LocalDate.of(2027, 4, 10), "c6");

        JsonNode view = recordOf(ana);
        assertThat(view.path("level").asText()).isEqualTo("STEADY");
        assertThat(view.path("levelRank").asInt()).isEqualTo(2);
        assertThat(view.path("loanLimitCents").asLong()).isEqualTo(300_000);
        assertThat(view.path("ladderTier").asInt()).isEqualTo(2);
        assertThat(view.at("/savings/onTimeMonths").asInt()).isEqualTo(6);
        assertThat(view.at("/savings/missedMonths").asInt()).isZero();
        assertThat(view.at("/instalments/onTime").asInt()).isEqualTo(3);
        assertThat(view.at("/instalments/late").asInt()).isZero();
        assertThat(view.at("/loans/repaidOnPlan").asInt()).isEqualTo(1);
        assertThat(view.path("onTimeBand").asText()).isEqualTo("95-100%");
        assertThat(view.path("next").path("level").asText()).isEqualTo("TRUSTED");
        assertThat(view.path("since").asText()).isEqualTo("2026-11-10");
        assertThat(levelEvents.moves(ana)).containsExactly("NEW>BUILDING@PROMOTION", "BUILDING>STEADY@PROMOTION");
        assertThat(creditRecord.levelOf(ana).level()).isEqualTo("STEADY");

        // The facts behind it, newest first, with no money in them.
        JsonNode history = getJson("/api/v1/members/me/record/history?limit=4", ana);
        assertThat(history.path("items")).hasSize(4);
        assertThat(history.path("nextBefore").isNull() || history.path("nextBefore").isNumber()).isTrue();
        assertThat(history.toString()).doesNotContain("amount").doesNotContain("Cents");
        assertThat(allKinds(ana)).contains("SAVINGS_DEPOSIT", "LOAN_DISBURSED", "INSTALMENT_PAID", "LOAN_REPAID");
    }

    @Test
    void aFactReportedAgainNeverCountsTwice() throws Exception {
        UUID ana = UUID.randomUUID();
        UUID goal = goalFor(ana);
        saveOn(ana, goal, LocalDate.of(2026, 11, 10), "c1");
        LoanSummary loan = disburse(ana, 300, 3, 9, LocalDate.of(2026, 11, 10));
        pay(loan, LocalDate.of(2026, 12, 9), "T1");
        long facts = factCount(ana);
        assertThat(facts).isEqualTo(3); // one deposit, one disbursement, one instalment

        String depositKey = jdbc.sql("SELECT fact_key FROM creditrecord.record_fact WHERE member_id = ? AND kind = 'SAVINGS_DEPOSIT'")
                .param(ana).query(String.class).single();
        // The same event again, and the same instalment reported by another event, change nothing.
        assertThat(creditRecord.ingest(RecordFact.savingsDeposit(depositKey, ana, LocalDate.of(2026, 11, 10))))
                .isEqualTo(IngestOutcome.DUPLICATE);
        assertThat(creditRecord.ingest(RecordFact.instalmentPaid(ana, loan.loanId(), 1, 30, LocalDate.of(2027, 1, 9))))
                .isEqualTo(IngestOutcome.DUPLICATE);
        assertThat(creditRecord.ingest(RecordFact.loanDisbursed("loan-disbursed:" + loan.loanId(), ana, loan.loanId(),
                LocalDate.of(2026, 11, 10)))).isEqualTo(IngestOutcome.DUPLICATE);

        assertThat(factCount(ana)).isEqualTo(facts);
        assertThat(recordOf(ana).at("/savings/onTimeMonths").asInt()).isEqualTo(1);
        assertThat(recordOf(ana).at("/instalments/onTime").asInt()).isEqualTo(1);
        assertThat(recordOf(ana).at("/instalments/late").asInt()).isZero();
        assertThat(recordOf(ana).at("/loans/disbursed").asInt()).isEqualTo(1);
    }

    @Test
    void aLoanLeftUnpaidIsRecordedAsMissedThenDefaultedAndOnlyThatLowersTheLevel() throws Exception {
        UUID ben = UUID.randomUUID();
        UUID goal = goalFor(ben);
        saveOn(ben, goal, LocalDate.of(2026, 8, 10), "c1");
        saveOn(ben, goal, LocalDate.of(2026, 9, 10), "c2");
        saveOn(ben, goal, LocalDate.of(2026, 10, 10), "c3");
        assertThat(recordOf(ben).path("level").asText()).isEqualTo("BUILDING");

        LoanSummary loan = disburse(ben, 300, 3, 9, LocalDate.of(2026, 11, 10));
        contacts.upsertContact(ben, "+15550002001", "en", "America/Los_Angeles");
        closeThrough(LocalDate.of(2026, 12, 20));                  // first instalment missed, still BUILDING
        JsonNode midway = recordOf(ben);
        assertThat(midway.path("level").asText()).isEqualTo("BUILDING");
        assertThat(midway.at("/instalments/openLate").asInt()).isEqualTo(1);
        assertThat(midway.path("defaults").asInt()).isZero();

        closeThrough(LocalDate.of(2027, 2, 15));                   // 68 days past due: the loan is in default

        JsonNode after = recordOf(ben);
        assertThat(after.path("level").asText()).isEqualTo("NEW");
        assertThat(after.path("defaults").asInt()).isEqualTo(1);
        assertThat(after.path("loanLimitCents").asLong()).isZero();
        assertThat(after.at("/instalments/openLate").asInt()).isZero();    // part of the default, not a new debt
        assertThat(after.at("/savings/onTimeMonths").asInt()).isEqualTo(3); // history stays true
        assertThat(levelEvents.moves(ben)).containsExactly("NEW>BUILDING@PROMOTION", "BUILDING>NEW@DEFAULT");
        assertThat(allKinds(ben)).contains("INSTALMENT_MISSED", "LOAN_DEFAULTED");
        assertThat(queries.loan(loan.loanId()).status().name()).isEqualTo("ACTIVE");
    }

    @Test
    void aMissCausedByOurOwnLateDecisionNeverReachesTheRecordButAMembersMissDoes() throws Exception {
        UUID cara = UUID.randomUUID();
        LoanSummary loan = disburse(cara, 300, 3, 9, LocalDate.of(2026, 11, 10));
        settle();
        LocalDate dueOne = LocalDate.of(2026, 12, 9);
        InstalmentMissed ours = new InstalmentMissed(Ids.newId(), loan.loanId(), cara, dueOne.plusDays(5), 1, 1, dueOne, "SLA_BREACH");
        InstalmentMissed hers = new InstalmentMissed(Ids.newId(), loan.loanId(), cara, dueOne.plusDays(35), 1, 2, dueOne.plusMonths(1), "MEMBER");
        InstalmentMissed plain = new InstalmentMissed(Ids.newId(), loan.loanId(), cara, dueOne.plusDays(66), 1, 3, dueOne.plusMonths(2));

        for (InstalmentMissed e : List.of(ours, ours, hers, hers, plain)) {          // at-least-once: each may arrive twice
            tx.executeWithoutResult(s -> publisher.publishEvent(e));
        }
        settle();

        assertThat(jdbc.sql("SELECT inst_seq FROM creditrecord.record_fact WHERE member_id = ? AND kind = 'INSTALMENT_MISSED' ORDER BY inst_seq")
                .param(cara).query(Integer.class).list()).as("only the instalments the member missed").containsExactly(2, 3);
        assertThat(recordOf(cara).at("/instalments/openLate").asInt()).isEqualTo(2);
    }

    // ---- hardship is never a miss ---------------------------------------------------------------------------------

    @Test
    void anApprovedHardshipPlanIsOnPlanAndDoesNotTouchTheLevel() throws Exception {
        UUID ana = UUID.randomUUID();
        UUID goal = goalFor(ana);
        saveOn(ana, goal, LocalDate.of(2026, 8, 10), "c1");
        saveOn(ana, goal, LocalDate.of(2026, 9, 10), "c2");
        saveOn(ana, goal, LocalDate.of(2026, 10, 10), "c3");
        assertThat(recordOf(ana).path("level").asText()).isEqualTo("BUILDING");

        LoanSummary loan = disburse(ana, 1_200, 8, 20, LocalDate.of(2026, 11, 10));
        contacts.upsertContact(ana, "+15550002002", "en", "America/Los_Angeles");
        LocalDate due = lending.currentSchedule(loan.loanId()).instalments().get(0).dueDate();
        LocalDate scanDate = due.minusDays(8);
        clock.set(scanDate);
        List<String> weeks = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            weeks.add("{\"weekStart\":\"" + scanDate.minusDays(91 - 7L * i) + "\",\"inflowCents\":" + (i < 9 ? 20_000 : 9_600) + "}");
        }
        mvc.perform(put("/internal/underwriting/members/{id}/income", ana).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", SERVICE_TOKEN).content("{\"consent\":true,\"weeks\":[" + String.join(",", weeks) + "]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/jobs/hardship/scan").header("Authorization", SERVICE_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"" + scanDate + "\",\"shardGroup\":0,\"shardCount\":1,\"limit\":500}"))
                .andExpect(status().isOk());
        settle();
        mvc.perform(post("/internal/jobs/hardship/sweep").header("Authorization", SERVICE_TOKEN)).andExpect(status().isOk());
        settle();

        JsonNode offer = json.readTree(mvc.perform(get("/api/v1/loans/{id}/hardship", loan.loanId()).header(MEMBER, ana.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("offer");
        UUID caseId = jdbc.sql("SELECT id FROM hardship.hardship_case WHERE loan_id = ?").param(loan.loanId()).query(UUID.class).single();
        mvc.perform(post("/api/v1/hardship/cases/{id}/choice", caseId).header(MEMBER, ana.toString())
                        .header("Idempotency-Key", "record-it-a").contentType(MediaType.APPLICATION_JSON).content(choice(offer, "A")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tier").value("AUTO_POLICY"));
        settle();
        assertThat(lending.currentSchedule(loan.loanId()).reason()).isEqualTo("HARDSHIP_MOVE_DATE");

        JsonNode view = recordOf(ana);
        assertThat(view.path("hardshipPlansOnPlan").asInt()).isEqualTo(1);
        assertThat(view.path("level").asText()).isEqualTo("BUILDING");
        assertThat(view.path("defaults").asInt()).isZero();
        assertThat(view.at("/instalments/late").asInt()).isZero();
        assertThat(view.at("/instalments/openLate").asInt()).isZero();
        assertThat(levelEvents.moves(ana)).containsExactly("NEW>BUILDING@PROMOTION");
        assertThat(allKinds(ana)).contains("HARDSHIP_PLAN_APPLIED").doesNotContain("INSTALMENT_MISSED", "LOAN_DEFAULTED");
        assertThat(jdbc.sql("SELECT count(*) FROM creditrecord.record_fact WHERE member_id = ? AND kind NOT IN ('SAVINGS_DEPOSIT', 'LOAN_DISBURSED', 'HARDSHIP_PLAN_APPLIED')")
                .param(ana).query(Integer.class).single()).isZero();

        // Paying on the new date is simply on time.
        LocalDate movedDue = lending.currentSchedule(loan.loanId()).instalments().get(0).dueDate();
        pay(loan, movedDue, "T-MOVED", 15_000);
        assertThat(recordOf(ana).at("/instalments/onTime").asInt()).isEqualTo(1);
        assertThat(recordOf(ana).at("/instalments/late").asInt()).isZero();
    }

    // ---- share, verify, revoke --------------------------------------------------------------------------------------

    @Test
    void shareThenVerifyThenRevokeThenVerifyFails() throws Exception {
        clock.set(LocalDate.of(2026, 11, 10));
        UUID ana = recordWithThreeSavingMonths();

        MvcResult created = mvc.perform(post("/api/v1/members/me/record/shares").header(MEMBER, ana.toString())
                        .header("Idempotency-Key", "share-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInDays\":14,\"detail\":\"FULL\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.detail").value("FULL"))
                .andExpect(jsonPath("$.token").isString()).andReturn();
        JsonNode share = json.readTree(created.getResponse().getContentAsString());
        String token = share.path("token").asText();
        UUID shareId = UUID.fromString(share.path("shareId").asText());
        assertThat(share.path("verifyPath").asText()).isEqualTo("/api/v1/records/verify/" + token);
        assertThat(token).doesNotContain(ana.toString());
        assertThat(share.path("expiresAt").asText()).isEqualTo("2026-11-24T20:00:00Z");

        // A partner, no sign-in, no headers.
        MvcResult verified = mvc.perform(get("/api/v1/records/verify/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.level").value("BUILDING"))
                .andExpect(jsonPath("$.since").value("2026-01-01"))
                .andExpect(jsonPath("$.loansRepaidOnPlan").value(0))
                .andExpect(jsonPath("$.onTimeBand").value("95-100%"))
                .andReturn();
        assertThat(verified.getResponse().getHeader("Cache-Control")).contains("max-age=5").contains("public");
        assertNothingLeaks(verified.getResponse().getContentAsString(), ana);

        // The member sees it listed, without its token.
        JsonNode listed = getJson("/api/v1/members/me/record/shares", ana);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(listed.toString()).doesNotContain(token).doesNotContain("token");

        // The same key and body is the same share; a different body is a conflict.
        mvc.perform(post("/api/v1/members/me/record/shares").header(MEMBER, ana.toString()).header("Idempotency-Key", "share-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInDays\":14,\"detail\":\"FULL\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.token").value(token));
        mvc.perform(post("/api/v1/members/me/record/shares").header(MEMBER, ana.toString()).header("Idempotency-Key", "share-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInDays\":14,\"detail\":\"LEVEL\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("idempotency-key-reused"));
        assertThat(getJson("/api/v1/members/me/record/shares", ana)).hasSize(1);

        // Someone else cannot revoke it, and cannot tell it exists.
        UUID stranger = UUID.randomUUID();
        mvc.perform(delete("/api/v1/members/me/record/shares/{id}", shareId).header(MEMBER, stranger.toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("share-not-found"));
        assertThat(getJson("/api/v1/members/me/record/shares", stranger)).isEmpty();
        mvc.perform(get("/api/v1/records/verify/{token}", token)).andExpect(status().isOk());

        // The owner revokes; the very next verification is the generic 404.
        mvc.perform(delete("/api/v1/members/me/record/shares/{id}", shareId).header(MEMBER, ana.toString()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/members/me/record/shares/{id}", shareId).header(MEMBER, ana.toString()))
                .andExpect(status().isNoContent());
        String revoked = mvc.perform(get("/api/v1/records/verify/{token}", token)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("record-not-found")).andReturn().getResponse().getContentAsString();
        assertThat(getJson("/api/v1/members/me/record/shares", ana).get(0).path("status").asText()).isEqualTo("REVOKED");

        // Garbage, a tampered token and a revoked one are indistinguishable.
        String garbage = notFoundBody("not-a-token");
        assertThat(revoked).isEqualTo(garbage);
        assertThat(revoked).doesNotContain(token).doesNotContain("not-a-token");
    }

    @Test
    void everyKindOfBadOrForgedTokenGetsTheSameGeneric404() throws Exception {
        clock.set(LocalDate.of(2026, 11, 10));
        UUID ana = recordWithThreeSavingMonths();
        String token = createShare(ana, "forge-1", 7, "LEVEL");
        String expected = notFoundBody("v1.k1.AAAA.AAAA");
        String[] parts = token.split("\\.");
        ShareClaims real = itCodec.verify(token, clock.now()).orElseThrow();

        List<String> bad = new ArrayList<>();
        bad.add(parts[0] + "." + parts[1] + "." + parts[2] + "." + flip(parts[3]));                                  // tampered signature
        bad.add(parts[0] + "." + parts[1] + "." + flip(parts[2]) + "." + parts[3]);                                  // tampered payload
        bad.add(parts[0] + ".k2." + parts[2] + "." + parts[3]);                                                      // wrong key id
        bad.add(ShareTokenCodec.of("k1", Map.of("k1", "another-secret-0123456789abcdefghijkl")).sign(real, null));  // wrong secret
        bad.add(ShareTokenCodec.of("k2", Map.of("k2", SECRET)).sign(real, null));                                    // right secret, unknown key id
        bad.add(itCodec.sign(new ShareClaims(real.shareId(), real.snapshotId(), real.issuedAt(), real.expiresAt(),
                com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareDetail.FULL), null));                      // widened detail, validly signed
        bad.add(itCodec.sign(new ShareClaims(real.shareId(), real.snapshotId(), real.issuedAt(),
                real.expiresAt().plusSeconds(86_400L * 300), real.detail()), null));                                 // longer life, validly signed
        bad.add(itCodec.sign(new ShareClaims(UUID.randomUUID(), real.snapshotId(), real.issuedAt(), real.expiresAt(),
                real.detail()), null));                                                                              // a share that does not exist
        bad.add("eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJzdWIiOiJ4In0.");                                            // JWT with alg none
        bad.add("v1.none." + parts[2] + ".");                                                                        // no signature
        bad.add(parts[0] + "." + parts[1] + "." + parts[2]);                                                         // a part missing
        bad.add("x".repeat(500));
        for (String candidate : bad) {
            assertThat(notFoundBody(candidate)).as(candidate).isEqualTo(expected);
        }
        mvc.perform(get("/api/v1/records/verify/{token}", token)).andExpect(status().isOk());         // the real one still works

        // Expired: one day, then two days pass.
        String shortLived = createShare(ana, "forge-2", 1, "LEVEL");
        mvc.perform(get("/api/v1/records/verify/{token}", shortLived)).andExpect(status().isOk());
        clock.set(LocalDate.of(2026, 11, 12));
        assertThat(notFoundBody(shortLived)).isEqualTo(expected);
        clock.set(LocalDate.of(2026, 11, 10));
    }

    @Test
    void aPartnerOnlySeesWhatTheMemberChoseAndNothingThatIdentifiesThem() throws Exception {
        clock.set(LocalDate.of(2026, 11, 10));
        UUID ana = recordWithThreeSavingMonths();

        JsonNode level = verify(createShare(ana, "detail-1", 7, "LEVEL"));
        assertThat(fieldNames(level)).containsExactlyInAnyOrder("valid", "level", "issuedAt", "expiresAt");

        JsonNode summary = verify(createShare(ana, "detail-2", 7, "SUMMARY"));
        assertThat(fieldNames(summary)).containsExactlyInAnyOrder("valid", "level", "since", "loansRepaidOnPlan", "issuedAt",
                "expiresAt");

        JsonNode full = verify(createShare(ana, "detail-3", 7, "FULL"));
        assertThat(PARTNER_FIELDS).containsAll(fieldNames(full));
        assertThat(fieldNames(full)).containsExactlyInAnyOrderElementsOf(PARTNER_FIELDS);
        assertNothingLeaks(full.toString(), ana);

        // What is stored for a snapshot holds no personal data either.
        assertThat(jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'creditrecord' AND table_name = 'record_snapshot'")
                .query(String.class).list()).containsExactlyInAnyOrder("id", "member_id", "level", "record_since", "loans_on_plan",
                "on_time_band", "defaults_at_capture", "captured_on", "created_at");
    }

    @Test
    void aDefaultAfterTheSnapshotMarksTheSharedRecordNotValid() throws Exception {
        clock.set(LocalDate.of(2026, 11, 10));
        UUID ana = recordWithThreeSavingMonths();
        String token = createShare(ana, "late-default", 7, "SUMMARY");
        assertThat(verify(token).path("valid").asBoolean()).isTrue();

        UUID loan = UUID.randomUUID();
        creditRecord.ingest(RecordFact.loanDefaulted(ana, loan, LocalDate.of(2026, 11, 11)));

        JsonNode after = verify(token);
        assertThat(after.path("valid").asBoolean()).isFalse();
        assertThat(after.path("level").asText()).isEqualTo("BUILDING"); // what was shared, never the new facts
        assertThat(recordOf(ana).path("level").asText()).isEqualTo("NEW");
        assertThat(levelEvents.moves(ana)).contains("BUILDING>NEW@DEFAULT");
    }

    // ---- who may read what -----------------------------------------------------------------------------------------

    @Test
    void aMemberReadsOnlyTheirOwnRecordAndTheDeskNeedsAnOfficer() throws Exception {
        clock.set(LocalDate.of(2026, 11, 10));
        UUID ana = recordWithThreeSavingMonths();
        UUID ben = UUID.randomUUID();

        assertThat(recordOf(ana).path("level").asText()).isEqualTo("BUILDING");
        JsonNode benView = recordOf(ben);                                  // no history yet: NEW, not a 404
        assertThat(benView.path("level").asText()).isEqualTo("NEW");
        assertThat(benView.path("loanLimitCents").asLong()).isZero();
        assertThat(benView.path("next").path("level").asText()).isEqualTo("BUILDING");
        assertThat(getJson("/api/v1/members/me/record/history", ben).path("items")).isEmpty();

        mvc.perform(get("/api/v1/members/me/record")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/members/me/record/shares")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/members/me/record").header("X-Seed-Staff-Id", UUID.randomUUID().toString())
                .header("X-Seed-Staff-Roles", "OFFICER")).andExpect(status().isForbidden());

        // Desk: officers only.
        mvc.perform(get("/api/v1/desk/members/{id}/record", ana).header(MEMBER, ben.toString())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/desk/members/{id}/record", ana)).andExpect(status().isUnauthorized());
        MvcResult desk = mvc.perform(get("/api/v1/desk/members/{id}/record", ana).header("X-Seed-Staff-Id", UUID.randomUUID().toString())
                        .header("X-Seed-Staff-Roles", "OFFICER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberId").value(ana.toString()))
                .andExpect(jsonPath("$.record.level").value("BUILDING")).andReturn();
        JsonNode deskView = json.readTree(desk.getResponse().getContentAsString());
        assertThat(deskView.path("recent")).hasSize(3);
        assertThat(deskView.toString().toLowerCase()).doesNotContain("risk").doesNotContain("score").doesNotContain("signal");
    }

    @Test
    void shareRequestsAreValidated() throws Exception {
        clock.set(LocalDate.of(2026, 11, 10));
        UUID ana = recordWithThreeSavingMonths();
        for (String body : new String[] {"{\"expiresInDays\":0,\"detail\":\"LEVEL\"}", "{\"expiresInDays\":31,\"detail\":\"LEVEL\"}",
                "{\"expiresInDays\":7,\"detail\":\"EVERYTHING\"}", "not json"}) {
            mvc.perform(post("/api/v1/members/me/record/shares").header(MEMBER, ana.toString()).header("Idempotency-Key", "v-" + body.hashCode())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/members/me/record/shares").header(MEMBER, ana.toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresInDays\":7,\"detail\":\"LEVEL\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/members/me/record/shares").header(MEMBER, UUID.randomUUID().toString()).header("Idempotency-Key", "empty")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInDays\":7,\"detail\":\"LEVEL\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("record-nothing-to-share"));
        // The longest allowed life is 30 days.
        mvc.perform(post("/api/v1/members/me/record/shares").header(MEMBER, ana.toString()).header("Idempotency-Key", "max")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInDays\":30,\"detail\":\"LEVEL\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.expiresAt").value("2026-12-10T20:00:00Z"));
    }

    @Test
    void aMonthWithoutADepositIsCountedWhenItEndsNotWhenTheNextDepositArrives() throws Exception {
        // 2025 dates keep this member behind every other test's members, so the counts below are exact.
        UUID ana = UUID.randomUUID();
        creditRecord.ingest(RecordFact.savingsDeposit("close-" + ana + "-1", ana, LocalDate.of(2025, 10, 5)));

        assertThat(creditRecord.closeSavingMonths(LocalDate.of(2025, 11, 20), 100)).isZero();   // October is over, nothing missed
        assertThat(creditRecord.closeSavingMonths(LocalDate.of(2026, 1, 3), 100)).isEqualTo(1); // November and December are over
        assertThat(recordOf(ana).at("/savings/missedMonths").asInt()).isEqualTo(2);
        assertThat(creditRecord.closeSavingMonths(LocalDate.of(2026, 1, 20), 100)).isZero();
        assertThat(recordOf(ana).at("/savings/missedMonths").asInt()).isEqualTo(2);
        assertThat(recordOf(ana).path("onTimeBand").asText()).isEqualTo("below 70%");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    /** In a transaction, so the goal-created event is delivered after commit like every other event. */
    private UUID goalFor(UUID member) {
        return tx.execute(status -> createGoal.execute(new CreateGoalUseCase.Command(member.toString(), "Mixer", Money.usd(1200.00),
                Money.usd(75.00), LocalDate.of(2027, 6, 1), "sub-" + member)).getId());
    }

    private void saveOn(UUID member, UUID goalId, LocalDate on, String capture) {
        clock.set(on);
        deposits.execute(new RecordDepositUseCase.Command(goalId, Money.usd(75.00), capture + "-" + member));
        settle();
    }

    private void pay(LoanSummary loan, LocalDate on, String txn) {
        pay(loan, on, txn, 10_000);
    }

    private void pay(LoanSummary loan, LocalDate on, String txn, long cents) {
        clock.set(on);
        repayments.recordReceipt(new Receipt(loan.loanId(), txn + "-" + loan.loanId(), Source.CHECKOUT, cents, 0, Instant.now(), null, "it"));
        settle();
    }

    private void closeThrough(LocalDate date) throws Exception {
        clock.set(date);
        mvc.perform(post("/internal/jobs/cob/catch-up").param("to", date.toString()).header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isOk());
        settle();
    }

    /** A member with three months of saving, recorded straight into the facade (the same path the wiring uses). */
    private UUID recordWithThreeSavingMonths() {
        UUID member = UUID.randomUUID();
        for (int month = 1; month <= 3; month++) {
            creditRecord.ingest(RecordFact.savingsDeposit("seed-" + member + "-" + month, member, LocalDate.of(2026, month, 5)));
        }
        return member;
    }

    private JsonNode recordOf(UUID member) throws Exception {
        return getJson("/api/v1/members/me/record", member);
    }

    private JsonNode getJson(String url, UUID member) throws Exception {
        return json.readTree(mvc.perform(get(url).header(MEMBER, member.toString())).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    private List<String> allKinds(UUID member) {
        return jdbc.sql("SELECT kind FROM creditrecord.record_fact WHERE member_id = ?").param(member).query(String.class).list();
    }

    private long factCount(UUID member) {
        return jdbc.sql("SELECT count(*) FROM creditrecord.record_fact WHERE member_id = ?").param(member).query(Long.class).single();
    }

    private String createShare(UUID member, String key, int days, String detail) throws Exception {
        String body = mvc.perform(post("/api/v1/members/me/record/shares").header(MEMBER, member.toString()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInDays\":" + days + ",\"detail\":\"" + detail + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("token").asText();
    }

    private JsonNode verify(String token) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/records/verify/{token}", token)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    private String notFoundBody(String token) throws Exception {
        return mvc.perform(get("/api/v1/records/verify/{token}", token)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("record-not-found")).andReturn().getResponse().getContentAsString();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void assertNothingLeaks(String body, UUID member) {
        assertThat(body).doesNotContain(member.toString());
        assertThat(body.toLowerCase()).doesNotContain("email").doesNotContain("name").doesNotContain("payer").doesNotContain("amount")
                .doesNotContain("cents").doesNotContain("memberid").doesNotContain("loanid").doesNotContain("phone");
        assertThat(PARTNER_FIELDS).containsAll(fieldNames(parse(body)));
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String flip(String part) {
        return (part.charAt(0) == 'A' ? "B" : "A") + part.substring(1);
    }

    private static String choice(JsonNode offer, String label) {
        for (JsonNode o : offer.path("options")) {
            if (o.path("label").asText().equals(label)) {
                return "{\"offerId\":\"" + offer.path("offerId").asText() + "\",\"optionId\":\"" + o.path("optionId").asText() + "\"}";
            }
        }
        throw new AssertionError("option " + label + " not offered: " + offer);
    }

}
