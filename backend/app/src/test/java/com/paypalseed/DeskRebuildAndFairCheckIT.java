package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.deskquery.api.DeskQueryFacade;
import com.paypalseed.deskquery.application.usecases.ApplicationProjector;
import com.paypalseed.deskquery.application.usecases.SavingMonthsRebuild;
import com.paypalseed.savings.application.ports.CreditedPayments.Source;
import com.paypalseed.savings.application.usecases.CreditPaymentUseCase;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService.RunFairCheckCommand;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Two desk-side pieces of the closing pass (ADR 0027): the saving months are rebuilt in the business zone from the deposits
 * savings holds, and the desk finds the newest fair check of one application without reading the list.
 */
class DeskRebuildAndFairCheckIT extends MoneyFlowSupport {

    @Autowired
    DeskQueryFacade desk;
    @Autowired
    SavingMonthsRebuild rebuild;
    @Autowired
    CreditPaymentUseCase credit;
    @Autowired
    ApplicationProjector applications;
    @Autowired
    UnderwritingFacade underwriting;
    @Autowired
    FairLoanCheckService fairCheck;

    private List<LocalDate> storedMonths(UUID member) {
        return jdbc.sql("SELECT month FROM deskquery.member_savings_month WHERE member_ref = ? ORDER BY month").param(member)
            .query(LocalDate.class).list();
    }

    private void creditedAt(String txn, String at) {
        jdbc.sql("UPDATE savings.credited_payment SET credited_at = ?::timestamptz WHERE paypal_txn_id = ?").param(at).param(txn).update();
    }

    // ---- the saving months ----

    @Test
    void aDepositNearTheUtcMonthBoundaryLandsInItsBusinessMonthAfterTheRebuild() throws Exception {
        UUID member = newMember();
        UUID goal = UUID.fromString(legacyGoal(member, 25).path("id").asText());
        String tag = member.toString().substring(0, 8);
        credit.credit(goal, "TXN-SEP-" + tag, 2_500, Source.SUBSCRIPTION);
        credit.credit(goal, "TXN-OCT-" + tag, 2_500, Source.SUBSCRIPTION);
        credit.credit(goal, "TXN-NOV-" + tag, 2_500, Source.SUBSCRIPTION);
        settle();
        creditedAt("TXN-SEP-" + tag, "2026-09-15T17:00:00Z");
        // 06:30 UTC on 1 November is 23:30 on 31 October in Los Angeles: October's deposit, which the UTC rule filed under November.
        creditedAt("TXN-OCT-" + tag, "2026-11-01T06:30:00Z");
        creditedAt("TXN-NOV-" + tag, "2026-11-20T17:00:00Z");

        // The state the old rule left: September and November, a streak of one, and the queue showing that streak.
        jdbc.sql("DELETE FROM deskquery.member_savings_month WHERE member_ref = ?").param(member).update();
        jdbc.sql("INSERT INTO deskquery.member_savings_month (member_ref, month) VALUES (?, '2026-09-01'), (?, '2026-11-01')")
            .param(member).param(member).update();
        jdbc.sql("UPDATE deskquery.member_savings SET streak_months = 1 WHERE member_ref = ?").param(member).update();
        applications.apply(new ApplicationProjector.Snapshot(UUID.randomUUID(), member, "SUBMITTED", 50_000, 6,
            Instant.parse("2026-11-02T10:00:00Z"), null, Instant.parse("2026-11-02T10:00:00Z")));
        jdbc.sql("UPDATE deskquery.application_view SET saving_streak_months = 1 WHERE member_ref = ?").param(member).update();

        assertThat(desk.rebuildSavingMonths()).isGreaterThanOrEqualTo(1);

        assertThat(storedMonths(member)).containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1));
        assertThat(jdbc.sql("SELECT streak_months FROM deskquery.member_savings WHERE member_ref = ?").param(member)
            .query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT saving_streak_months FROM deskquery.application_view WHERE member_ref = ?").param(member)
            .query(Integer.class).single()).as("the queue's streak").isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM deskquery.member_activity WHERE member_ref = ? AND kind = 'DEPOSIT'").param(member)
            .query(Integer.class).single()).as("a deposit is listed once, however it reached the list").isEqualTo(3);

        // A second run changes nothing.
        desk.rebuildSavingMonths();
        assertThat(storedMonths(member)).containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1));
    }

    @Test
    void theStartUpRebuildRanOnceAtBootAndIsNotRepeated() {
        assertThat(jdbc.sql("SELECT count(*) FROM deskquery.rebuild_done WHERE name = ?").param(SavingMonthsRebuild.NAME)
            .query(Integer.class).single()).as("the runner wrote its marker when the application started").isEqualTo(1);
        assertThat(rebuild.runOnce()).as("already done").isFalse();

        jdbc.sql("DELETE FROM deskquery.rebuild_done").update();
        assertThat(rebuild.runOnce()).isTrue();
        assertThat(rebuild.runOnce()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM deskquery.rebuild_done").query(Integer.class).single()).isEqualTo(1);
    }

    // ---- the fair check beside an application ----

    private FairCheckCardView fairCheckFor(UUID member, UUID application) {
        return fairCheck.runFairLoanCheck(member, new RunFairCheckCommand(application, "Commercial stand mixer for home bakery",
            120_000L, 8, List.of(new IncomeMonthDto("2026-05", 165_000L), new IncomeMonthDto("2026-06", 172_000L),
                new IncomeMonthDto("2026-07", 118_000L), new IncomeMonthDto("2026-08", 165_000L),
                new IncomeMonthDto("2026-09", 170_000L), new IncomeMonthDto("2026-10", 170_000L)),
            160_000L, 90_000L, 0, false));
    }

    @Test
    void theDeskGetsTheNewestFairCheckOfOneApplicationOnly() throws Exception {
        UUID member = newMember();
        underwriting.grantConsent(member);
        UUID application = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        fairCheckFor(member, application);
        Thread.sleep(20);
        FairCheckCardView newest = fairCheckFor(member, application);
        Thread.sleep(20);
        fairCheckFor(member, other);
        UUID officer = UUID.randomUUID();

        for (String role : List.of("OFFICER", "CHECKER", "ADMIN")) {
            mvc.perform(asStaff(get("/api/v1/desk/underwriting/assessments/latest").param("applicationId", application.toString()), officer, role))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessmentId").value(newest.assessmentId().toString()))
                .andExpect(jsonPath("$.applicationId").value(application.toString()));
        }
        JsonNode one = read(mvc.perform(asStaff(get("/api/v1/desk/underwriting/assessments/latest")
            .param("applicationId", other.toString()), officer, "OFFICER")).andExpect(status().isOk()).andReturn());
        assertThat(one.path("applicationId").asText()).isEqualTo(other.toString());
        assertThat(one.path("assessmentId").asText()).isNotEqualTo(newest.assessmentId().toString());
    }

    @Test
    void anApplicationWithoutAFairCheckIs404AndOnlyStaffMayAsk() throws Exception {
        UUID member = newMember();
        UUID officer = UUID.randomUUID();
        String none = UUID.randomUUID().toString();

        mvc.perform(asStaff(get("/api/v1/desk/underwriting/assessments/latest").param("applicationId", none), officer, "OFFICER"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("assessment-not-found")));
        mvc.perform(asMember(get("/api/v1/desk/underwriting/assessments/latest").param("applicationId", none), member))
            .andExpect(status().isForbidden());
        mvc.perform(asStaff(get("/api/v1/desk/underwriting/assessments/latest").param("applicationId", none), officer, ""))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/desk/underwriting/assessments/latest").param("applicationId", none)).andExpect(status().isUnauthorized());
        mvc.perform(asStaff(get("/api/v1/desk/underwriting/assessments/latest"), officer, "OFFICER")).andExpect(status().is4xxClientError());
        mvc.perform(asStaff(get("/api/v1/desk/underwriting/assessments/latest").param("applicationId", "not-a-uuid"), officer, "OFFICER"))
            .andExpect(status().is4xxClientError());
    }
}
