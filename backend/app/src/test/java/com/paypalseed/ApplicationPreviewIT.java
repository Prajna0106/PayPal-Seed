package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.lending.api.dto.LendingDtos.InstalmentLine;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.support.FakePayPal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The application preview shows a member the monthly payment and the schedule before they apply, from the same code
 * that sizes the application and builds the schedule at disbursement, and stores nothing.
 */
class ApplicationPreviewIT extends MoneyFlowSupport {

    private static final String PREVIEW = "/api/v1/loans/applications/preview";

    @Autowired
    MicroloanApplicationService microloans;
    @Autowired
    CreateGoalUseCase createGoal;
    @Autowired
    RecordDepositUseCase deposits;

    /** A member with four $75 deposits: eligible at the first rung, safe instalment $150 with $1,600 income. */
    private UUID savingMember() {
        UUID member = newMember();
        clock.set(LocalDate.of(2026, 11, 1));
        UUID goal = createGoal.execute(new CreateGoalUseCase.Command(member.toString(), "Mixer", Money.usd(1200), Money.usd(75), null,
            "sub-" + member)).getId();
        for (int i = 1; i <= 4; i++) {
            deposits.execute(new RecordDepositUseCase.Command(goal, Money.usd(75), "cap-" + i + "-" + member));
        }
        settle();
        return member;
    }

    private static String body(long cents, int term) {
        return "{\"requestedAmountCents\":" + cents + ",\"termMonths\":" + term + ",\"preferredDueDay\":15,"
            + "\"verifiedMonthlyIncomeCents\":160000}";
    }

    private JsonNode preview(UUID member, String body) throws Exception {
        return read(mvc.perform(asMember(post(PREVIEW), member).content(body)).andExpect(status().isOk()).andReturn());
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void aValidPreviewIsTheScheduleTheLoanGetsAndStoresNothing() throws Exception {
        UUID ana = savingMember();
        paypal.payoutOutcome = FakePayPal.PayoutOutcome.INSTANT_SUCCESS;
        long applicationsBefore = count("lending.loan_application");
        long loansBefore = count("lending.loan");
        long journalBefore = count("ledger.journal_entry");

        JsonNode view = preview(ana, body(120_000, 8));

        assertThat(view.path("valid").asBoolean()).isTrue();
        assertThat(view.path("outcome").asText()).isEqualTo("APPROVED");
        assertThat(view.path("monthlyInstalmentCents").asLong()).isEqualTo(15_000);
        assertThat(view.path("totalRepayableCents").asLong()).isEqualTo(120_000);
        assertThat(view.path("dueDay").asInt()).isEqualTo(15);
        assertThat(view.path("assumedDisbursementDate").asText()).isEqualTo("2026-11-01");
        assertThat(view.path("instalments")).hasSize(8);
        assertThat(view.path("firstDueDate").asText()).isEqualTo(view.path("instalments").get(0).path("dueDate").asText());
        assertThat(view.path("reasons").toString()).doesNotContainIgnoringCase("score");
        assertThat(count("lending.loan_application")).isEqualTo(applicationsBefore);
        assertThat(count("lending.loan")).isEqualTo(loansBefore);
        assertThat(count("ledger.journal_entry")).isEqualTo(journalBefore);

        // Applying for the same thing and being paid the same day gives exactly the schedule that was shown
        LoanApplicationView submitted = microloans.submitApplication(ana,
            new SubmitApplicationCommand("Mixer", 120_000L, 8, 15, WalletType.PAYPAL, true, OptionalLong.of(160_000L)));
        microloans.approveApplication(submitted.id(), UUID.randomUUID());
        microloans.approveApplication(submitted.id(), UUID.randomUUID());
        LoanApplicationView disbursed = microloans.disburseApplication(submitted.id());
        settle();
        assertThat(disbursed.status()).isEqualTo("DISBURSED");

        List<InstalmentLine> created = lending.currentSchedule(disbursed.loanId()).instalments();
        List<String> shown = new ArrayList<>();
        view.path("instalments").forEach(i -> shown.add(i.path("seq").asInt() + "|" + i.path("dueDate").asText() + "|"
            + i.path("amountCents").asLong()));
        assertThat(shown).containsExactlyElementsOf(
            created.stream().map(i -> i.seq() + "|" + i.dueDate() + "|" + i.amountCents()).toList());
        assertThat(view.path("monthlyInstalmentCents").asLong()).isEqualTo(submitted.monthlyInstalmentCents());
    }

    @Test
    void anAmountOverTheLimitIsAnsweredInWordsWithASaferAlternative() throws Exception {
        UUID ana = savingMember();

        JsonNode view = preview(ana, body(160_000, 8));

        assertThat(view.path("valid").asBoolean()).isFalse();
        assertThat(view.path("outcome").asText()).isEqualTo("ABOVE_LIMIT");
        assertThat(view.path("instalments")).isEmpty();
        assertThat(view.hasNonNull("firstDueDate")).isFalse();
        assertThat(view.path("limitCents").asLong()).isEqualTo(150_000);
        assertThat(view.path("suggestedAmountCents").asLong()).isEqualTo(150_000);
        assertThat(view.path("suggestedTermMonths").asInt()).isEqualTo(10);
        assertThat(view.path("reasons").get(0).asText()).contains("exceeds").contains("$1500.00");
    }

    @Test
    void aTermOutsideTheRangeAndAnInstalmentAboveTheCapAreAnsweredInWords() throws Exception {
        UUID ana = savingMember();

        JsonNode shortTerm = preview(ana, body(60_000, 2));
        assertThat(shortTerm.path("valid").asBoolean()).isFalse();
        assertThat(shortTerm.path("outcome").asText()).isEqualTo("TERM_OUT_OF_RANGE");
        assertThat(shortTerm.path("suggestedTermMonths").asInt()).isEqualTo(3);
        assertThat(shortTerm.path("instalments")).isEmpty();

        JsonNode tooHigh = preview(ana, body(120_000, 4));
        assertThat(tooHigh.path("valid").asBoolean()).isFalse();
        assertThat(tooHigh.path("outcome").asText()).isEqualTo("INSTALMENT_ABOVE_CAP");
        assertThat(tooHigh.path("monthlyInstalmentCents").asLong()).isEqualTo(30_000);
        assertThat(tooHigh.path("maxMonthlyInstalmentCents").asLong()).isEqualTo(15_000);
        assertThat(tooHigh.path("suggestedTermMonths").asInt()).isEqualTo(8);
        assertThat(tooHigh.path("reasons").get(0).asText()).contains("safe monthly cap");
    }

    @Test
    void nonsenseInputIsABadRequestNotAPreview() throws Exception {
        UUID ana = savingMember();

        mvc.perform(asMember(post(PREVIEW), ana).content(body(120_000, 70))).andExpect(status().isBadRequest());
        mvc.perform(asMember(post(PREVIEW), ana).content(body(0, 8))).andExpect(status().isBadRequest());
        mvc.perform(asMember(post(PREVIEW), ana).content("{\"requestedAmountCents\":120000,\"termMonths\":8,\"preferredDueDay\":29}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void onlyAMemberMayPreviewAndOnlyWithAVerifiedIdentity() throws Exception {
        UUID officer = UUID.randomUUID();
        UUID member = newMember();

        mvc.perform(asStaff(post(PREVIEW), officer, "OFFICER").content(body(120_000, 8))).andExpect(status().isForbidden());
        mvc.perform(asStaff(post(PREVIEW), officer, "").content(body(120_000, 8))).andExpect(status().isForbidden());
        mvc.perform(post(PREVIEW).contentType(MediaType.APPLICATION_JSON).content(body(120_000, 8)))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(PREVIEW).header("X-Member-Id", member.toString()).contentType(MediaType.APPLICATION_JSON)
            .content(body(120_000, 8))).andExpect(status().isUnauthorized());
    }

    @Test
    void aMemberCanOnlyEverPreviewTheirOwnLoan() throws Exception {
        UUID ana = savingMember();
        UUID bea = newMember();

        mvc.perform(asMember(post(PREVIEW), bea).content("{\"memberId\":\"" + ana + "\",\"requestedAmountCents\":120000,\"termMonths\":8}"))
            .andExpect(status().isNotFound());
        mvc.perform(asMember(post(PREVIEW), ana).content("{\"memberId\":\"" + ana + "\",\"requestedAmountCents\":120000,"
            + "\"termMonths\":8,\"verifiedMonthlyIncomeCents\":160000}")).andExpect(status().isOk());

        // Bea has not saved: the preview is sized from Bea's own record, never from Ana's
        JsonNode view = preview(bea, body(120_000, 8));
        assertThat(view.path("valid").asBoolean()).isFalse();
        assertThat(view.path("outcome").asText()).isEqualTo("NOT_ELIGIBLE");
        assertThat(view.path("instalments")).isEmpty();
        assertThat(view.hasNonNull("suggestedAmountCents")).isFalse();
        assertThat(view.path("reasons")).isNotEmpty();
    }
}
