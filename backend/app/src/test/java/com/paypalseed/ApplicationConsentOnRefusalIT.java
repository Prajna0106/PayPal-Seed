package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.payments.application.ports.PayPalGateway.SearchedTransaction;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.support.FakePayPal;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * With income read from PayPal, the fair-check consent has to be recorded before the application can be checked
 * (the check needs the income). The consent is for the application, so an application that is refused takes back the
 * consent it alone created, and the income read for it is forgotten; a consent the member gave earlier is never touched
 * (ADR 0024, closing pass).
 */
@TestPropertySource(properties = {
        "seed.paypal.transaction-search.enabled=true",
        "seed.underwriting.income.source=TRANSACTION_SEARCH"})
class ApplicationConsentOnRefusalIT extends MoneyFlowSupport {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @Autowired
    UnderwritingFacade underwriting;
    @Autowired
    CreateGoalUseCase createGoal;
    @Autowired
    RecordDepositUseCase deposits;

    @BeforeEach
    void seedPayPal() {
        clock.set(TODAY);
        paypal.transactions.clear();
        paypal.transactions.addAll(List.of(credit("I1", "2026-09-05T18:00:00Z", 300_000), credit("I2", "2026-10-05T18:00:00Z", 300_000)));
    }

    private static SearchedTransaction credit(String id, String at, long amount) {
        return new SearchedTransaction(id, "T0006", "S", Instant.parse(at), amount, 0, Optional.empty(),
                Optional.of("PAYER-" + id), true, "Gig " + id);
    }

    private UUID savingMember() {
        UUID member = newMember();
        clock.set(LocalDate.of(2026, 11, 1));
        UUID goal = createGoal.execute(new CreateGoalUseCase.Command(member.toString(), "Mixer", Money.usd(1200), Money.usd(75), null,
                "sub-" + member)).getId();
        for (int i = 1; i <= 4; i++) {
            deposits.execute(new RecordDepositUseCase.Command(goal, Money.usd(75), "cap-" + i + "-" + member));
        }
        settle();
        clock.set(TODAY);
        return member;
    }

    private String body(long cents) {
        return """
                {"purpose":"Commercial stand mixer","requestedAmountCents":%d,"termMonths":8,"preferredDueDay":15,
                 "payoutWallet":"PAYPAL","fairCheckConsented":true}""".formatted(cents);
    }

    private long count(String table, UUID member) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE member_id = :m").param("m", member).query(Long.class).single();
    }

    @Test
    void aRefusedApplicationLeavesNoConsentAndNoIncomeBehind() throws Exception {
        UUID member = savingMember();

        mvc.perform(asMember(post("/api/v1/loans/applications"), member).content(body(6_000_000))).andExpect(status().is4xxClientError());
        settle();

        assertThat(count("lending.loan_application", member)).isZero();
        assertThat(underwriting.getConsentStatus(member).consented()).as("the consent was for an application that never was").isFalse();
        assertThat(identity.hasActiveConsent(member, ConsentType.FAIR_CHECK)).isFalse();
        assertThat(count("underwriting.income_week", member)).as("and the income read for it is forgotten").isZero();

        // a sensible application afterwards asks again and is accepted with its consent
        mvc.perform(asMember(post("/api/v1/loans/applications"), member).content(body(120_000))).andExpect(status().isCreated());
        settle();
        assertThat(count("lending.loan_application", member)).isEqualTo(1);
        assertThat(underwriting.getConsentStatus(member).consented()).isTrue();
        assertThat(identity.hasActiveConsent(member, ConsentType.FAIR_CHECK)).isTrue();
        assertThat(count("underwriting.income_week", member)).isEqualTo(13);
    }

    @Test
    void aConsentGivenWithAnEarlierAcceptedApplicationSurvivesALaterRefusal() throws Exception {
        UUID member = savingMember();
        mvc.perform(asMember(post("/api/v1/loans/applications"), member).content(body(120_000))).andExpect(status().isCreated());
        settle();

        mvc.perform(asMember(post("/api/v1/loans/applications"), member).content(body(6_000_000))).andExpect(status().is4xxClientError());
        settle();

        assertThat(underwriting.getConsentStatus(member).consented()).isTrue();
        assertThat(identity.hasActiveConsent(member, ConsentType.FAIR_CHECK)).isTrue();
        assertThat(count("underwriting.income_week", member)).isEqualTo(13);
        assertThat(count("lending.loan_application", member)).isEqualTo(1);
    }

    @Test
    void aPreviewSaysWhenPayPalCouldNotBeReadAndStillAnswersFromTheStoredIncome() throws Exception {
        UUID member = savingMember();
        mvc.perform(asMember(post("/api/v1/loans/applications"), member).content(body(120_000))).andExpect(status().isCreated());
        settle();
        String preview = "{\"requestedAmountCents\":120000,\"termMonths\":8}";

        mvc.perform(asMember(post("/api/v1/loans/applications/preview"), member).content(preview))
                .andExpect(status().isOk()).andExpect(jsonPath("$.incomeStale").value(false)).andExpect(jsonPath("$.valid").value(true));

        clock.set(TODAY.plusDays(2)); // the stored income is now older than a day, so the preview tries PayPal again
        paypal.failNext("searchTransactions", FakePayPal.unavailable());
        mvc.perform(asMember(post("/api/v1/loans/applications/preview"), member).content(preview))
                .andExpect(status().isOk()).andExpect(jsonPath("$.incomeStale").value(true)).andExpect(jsonPath("$.valid").value(true));

        mvc.perform(asMember(post("/api/v1/loans/applications/preview"), member).content(preview))
                .andExpect(status().isOk()).andExpect(jsonPath("$.incomeStale").value(false));
    }
}
