package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.payments.application.ports.PayPalGateway.SearchedTransaction;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.support.FakePayPal;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.application.ports.IncomeTransactionSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * With income read from PayPal Transaction Search, submitting a loan application reads PayPal first, with no database
 * transaction open, and only then runs the transaction on the stored income (ADR 0026). A PayPal outage therefore
 * holds no connection and leaves no application, consent or income behind.
 */
@TestPropertySource(properties = {
        "seed.paypal.transaction-search.enabled=true",
        "seed.underwriting.income.source=TRANSACTION_SEARCH"})
class SubmitIncomeBeforeTransactionIT extends MoneyFlowSupport {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @SpyBean
    IncomeTransactionSource incomeSource;

    @Autowired
    MicroloanApplicationService microloans;
    @Autowired
    UnderwritingFacade underwriting;
    @Autowired
    CreateGoalUseCase createGoal;
    @Autowired
    RecordDepositUseCase deposits;

    /** For each PayPal read: was a transaction open on the calling thread? */
    private final List<Boolean> transactionOpenDuringRead = new CopyOnWriteArrayList<>();

    @BeforeEach
    void seedPayPalAndWatchTheReads() throws Throwable {
        clock.set(TODAY);
        paypal.transactions.clear();
        paypal.transactions.addAll(List.of(
                credit("I1", "2026-09-05T18:00:00Z", 300_000),
                credit("I2", "2026-10-05T18:00:00Z", 300_000)));
        transactionOpenDuringRead.clear();
        doAnswer(call -> {
            transactionOpenDuringRead.add(TransactionSynchronizationManager.isActualTransactionActive());
            return call.callRealMethod();
        }).when(incomeSource).credits(any(), any(), any());
    }

    private static SearchedTransaction credit(String id, String at, long amount) {
        return new SearchedTransaction(id, "T0006", "S", Instant.parse(at), amount, 0, Optional.empty(),
                Optional.of("PAYER-" + id), true, "Gig " + id);
    }

    /** A member with four $75 deposits, so $1,200 over 8 months is inside the first rung. */
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

    private static final String SUBMIT = """
            {"purpose":"Commercial stand mixer","requestedAmountCents":120000,"termMonths":8,"preferredDueDay":15,
             "payoutWallet":"PAYPAL","fairCheckConsented":true}""";

    private LoanApplicationView submit(UUID member) {
        return microloans.submitApplication(member, new SubmitApplicationCommand("Commercial stand mixer", 120_000L, 8, 15,
                WalletType.PAYPAL, true, OptionalLong.empty()));
    }

    private long count(String table, UUID member) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE member_id = :m").param("m", member)
                .query(Long.class).single();
    }

    @Test
    void payPalIsReadWithNoTransactionOpenAndTheApplicationIsDecidedOnTheStoredIncome() {
        UUID member = savingMember();

        LoanApplicationView view = submit(member);

        assertThat(view.status()).isEqualTo("SUBMITTED");
        assertThat(paypal.searches).hasSize(3);
        assertThat(transactionOpenDuringRead).containsExactly(false);
        assertThat(count("lending.loan_application", member)).isEqualTo(1);
        assertThat(count("underwriting.fair_loan_check_assessment", member)).isEqualTo(1);
        assertThat(count("underwriting.income_week", member)).isEqualTo(13);
        assertThat(underwriting.getConsentStatus(member).consented()).isTrue();
        // the consent the member gave in the request is on their list too (ADR 0026)
        assertThat(identity.hasActiveConsent(member, ConsentType.FAIR_CHECK)).isTrue();
    }

    @Test
    void aPayPalOutageWithNoStoredIncomeRefusesBeforeAnythingIsWrittenAndALaterTryWorks() throws Exception {
        UUID member = savingMember();

        paypal.failNext("searchTransactions", FakePayPal.unavailable());
        mvc.perform(asMember(post("/api/v1/loans/applications"), member).content(SUBMIT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("income-source-unavailable")))
                .andExpect(content().string(not(containsString("PayPal 503"))));

        assertThat(transactionOpenDuringRead).containsExactly(false);
        assertThat(count("lending.loan_application", member)).isZero();
        assertThat(count("underwriting.fair_loan_check_assessment", member)).isZero();
        assertThat(count("underwriting.income_week", member)).isZero();
        assertThat(count("underwriting.income_consent", member)).isZero();
        assertThat(identity.hasActiveConsent(member, ConsentType.FAIR_CHECK)).isFalse();

        mvc.perform(asMember(post("/api/v1/loans/applications"), member).content(SUBMIT))
                .andExpect(status().isCreated());
        assertThat(count("lending.loan_application", member)).isEqualTo(1);
        assertThat(count("underwriting.income_week", member)).isEqualTo(13);
    }

    @Test
    void aSlowPayPalReadHoldsNoDatabaseTransactionAndNoApplicationExistsWhileItRuns() throws Exception {
        UUID member = savingMember();
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            transactionOpenDuringRead.add(TransactionSynchronizationManager.isActualTransactionActive());
            readStarted.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(incomeSource).credits(any(), any(), any());

        CompletableFuture<LoanApplicationView> submission = CompletableFuture.supplyAsync(() -> submit(member));
        assertThat(readStarted.await(30, TimeUnit.SECONDS)).isTrue();

        // PayPal is "slow" right now: nothing is written and no connection sits inside an open transaction.
        assertThat(count("lending.loan_application", member)).isZero();
        long idleInTransaction = jdbc.sql("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE datname = current_database() AND state = 'idle in transaction' AND pid <> pg_backend_pid()""")
                .query(Long.class).single();
        assertThat(idleInTransaction).isZero();

        release.countDown();
        assertThat(submission.get(60, TimeUnit.SECONDS).status()).isEqualTo("SUBMITTED");
        assertThat(transactionOpenDuringRead).containsExactly(false);
    }

    @Test
    void freshStoredIncomeIsUsedWhenPayPalIsDownAndNothingIsReadAgain() {
        UUID member = savingMember();
        submit(member);
        assertThat(paypal.searches).hasSize(3);

        paypal.failNext("searchTransactions", FakePayPal.unavailable());
        LoanApplicationView second = submit(member);

        assertThat(second.status()).isEqualTo("SUBMITTED");
        assertThat(paypal.searches).hasSize(3);
        assertThat(count("lending.loan_application", member)).isEqualTo(2);
        // repeating the request is safe: one income snapshot, one fair-check line on the list
        assertThat(count("underwriting.income_week", member)).isEqualTo(13);
        assertThat(identity.consents(member).stream().filter(c -> c.type() == ConsentType.FAIR_CHECK)).hasSize(1);
    }

    @Test
    void staleStoredIncomeIsNeverUsedWhenPayPalIsDown() throws Exception {
        UUID member = savingMember();
        submit(member);

        clock.set(TODAY.plusDays(2));
        paypal.failNext("searchTransactions", FakePayPal.unavailable());
        mvc.perform(asMember(post("/api/v1/loans/applications"), member).content(SUBMIT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("income-source-unavailable")));

        assertThat(count("lending.loan_application", member)).isEqualTo(1);
        assertThat(count("underwriting.fair_loan_check_assessment", member)).isEqualTo(1);
        assertThat(transactionOpenDuringRead).containsExactly(false, false);
    }

    @Test
    void aPreviewDuringAnOutageStillAnswersFromTheStoredIncome() throws Exception {
        UUID member = savingMember();
        submit(member);

        clock.set(TODAY.plusDays(2));
        paypal.failNext("searchTransactions", FakePayPal.unavailable());
        mvc.perform(asMember(post("/api/v1/loans/applications/preview"), member)
                        .content("{\"requestedAmountCents\":120000,\"termMonths\":8,\"preferredDueDay\":15}"))
                .andExpect(status().isOk());

        assertThat(count("lending.loan_application", member)).isEqualTo(1);
    }
}
