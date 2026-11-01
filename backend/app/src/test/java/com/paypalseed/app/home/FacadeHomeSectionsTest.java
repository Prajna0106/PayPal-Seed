package com.paypalseed.app.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paypalseed.app.home.HomeView.HardshipSection;
import com.paypalseed.app.home.HomeView.LendingSection;
import com.paypalseed.app.home.HomeView.SavingsSection;
import com.paypalseed.creditrecord.api.CreditRecordFacade;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.NextStepView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.RecordView;
import com.paypalseed.engines.schedule.MandateReconciliation.MandateStatus;
import com.paypalseed.hardship.api.dto.HardshipDtos.MemberCaseView;
import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberLoanView;
import com.paypalseed.lending.api.dto.LendingDtos.NextDue;
import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.MandateView;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The mapping from facades to sections, with every facade faked. */
class FacadeHomeSectionsTest {

    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LOAN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final HomeSections.Context CONTEXT = new HomeSections.Context(MEMBER, TODAY, "en");

    private SavingsFacade savings;
    private PaymentsFacade payments;
    private CreditRecordFacade creditRecord;
    private MemberReads reads;
    private ObjectProvider<MemberReads> provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        savings = mock(SavingsFacade.class);
        payments = mock(PaymentsFacade.class);
        creditRecord = mock(CreditRecordFacade.class);
        reads = mock(MemberReads.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(reads);
    }

    private FacadeHomeSections sections(ObjectProvider<MemberReads> source) {
        return new FacadeHomeSections(mock(IdentityFacade.class), savings, payments, mock(UnderwritingFacade.class),
            creditRecord, source);
    }

    private static EligibilityView eligibility() {
        return new EligibilityView(MEMBER, true, "LEVEL_1", 1, 150_000, 120_000, 15_000, 3, 18, 4, 7_500, false, 0, List.of("ok"));
    }

    @Test
    void theActiveLoanShowsItsFirstOwedInstalmentAndTheAutopayState() {
        when(reads.eligibility(MEMBER)).thenReturn(eligibility());
        when(reads.loans(MEMBER)).thenReturn(List.of(
            new MemberLoanView(UUID.randomUUID(), "REPAID", 100_000, 0, null, false),
            new MemberLoanView(LOAN, "ACTIVE", 120_000, 90_000, new NextDue(2, LocalDate.of(2026, 11, 20), 15_000, 10_000), true)));
        when(reads.applications(MEMBER)).thenReturn(List.of());
        when(payments.mandateOf(LOAN)).thenReturn(Optional.of(new MandateView(UUID.randomUUID(), LOAN, MandateStatus.ACTIVE,
            Optional.of("I-SUB"), Optional.empty(), 150, Optional.of(LocalDate.of(2026, 11, 20)))));

        LendingSection section = sections(provider).lending(CONTEXT).orElseThrow();

        assertThat(section.eligibility().eligible()).isTrue();
        assertThat(section.eligibility().maxLoan().cents()).isEqualTo(120_000);
        assertThat(section.activeLoan().loanId()).isEqualTo(LOAN);
        assertThat(section.activeLoan().outstanding().cents()).isEqualTo(90_000);
        assertThat(section.activeLoan().nextPayment().seq()).isEqualTo(2);
        assertThat(section.activeLoan().nextPayment().dueDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(section.activeLoan().nextPayment().amount().cents()).isEqualTo(15_000);
        assertThat(section.activeLoan().nextPayment().remaining().cents()).isEqualTo(10_000);
        assertThat(section.activeLoan().autopay().status()).isEqualTo("ACTIVE");
        assertThat(section.activeLoan().autopay().nextBillingDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(section.openApplication()).isNull();
    }

    @Test
    void aMemberWithoutALoanHasNoActiveLoanButStillSeesTheirOpenApplication() {
        when(reads.eligibility(MEMBER)).thenReturn(eligibility());
        when(reads.loans(MEMBER)).thenReturn(List.of());
        UUID application = UUID.randomUUID();
        when(reads.applications(MEMBER)).thenReturn(List.of(
            application(UUID.randomUUID(), "REJECTED"), application(application, "PENDING_SECOND_APPROVAL")));

        LendingSection section = sections(provider).lending(CONTEXT).orElseThrow();

        assertThat(section.activeLoan()).isNull();
        assertThat(section.openApplication().applicationId()).isEqualTo(application);
        assertThat(section.openApplication().status()).isEqualTo("PENDING_SECOND_APPROVAL");
        assertThat(section.openApplication().requested().cents()).isEqualTo(120_000);
    }

    @Test
    void anAutopayLessLoanHasNoAutopayCard() {
        when(reads.eligibility(MEMBER)).thenReturn(eligibility());
        when(reads.loans(MEMBER)).thenReturn(List.of(new MemberLoanView(LOAN, "ACTIVE", 120_000, 90_000, null, false)));
        when(reads.applications(MEMBER)).thenReturn(List.of());
        when(payments.mandateOf(LOAN)).thenReturn(Optional.empty());

        LendingSection section = sections(provider).lending(CONTEXT).orElseThrow();

        assertThat(section.activeLoan().autopay()).isNull();
        assertThat(section.activeLoan().nextPayment()).isNull();
    }

    @Test
    void hardshipOffersWaitingAreFlaggedAndNoRiskLevelIsCarried() {
        UUID caseId = UUID.randomUUID();
        when(reads.openHardshipCase(MEMBER)).thenReturn(Optional.of(
            new MemberCaseView(caseId, LOAN, "OUTREACH_SENT", LocalDate.of(2026, 11, 20), 15_000, true)));

        HardshipSection section = sections(provider).hardship(CONTEXT).orElseThrow();

        assertThat(section.offerWaiting()).isTrue();
        assertThat(section.caseId()).isEqualTo(caseId);
        assertThat(section.loanId()).isEqualTo(LOAN);
        assertThat(section.status()).isEqualTo("OUTREACH_SENT");
        assertThat(section.dueDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(section.amountAtStake().cents()).isEqualTo(15_000);
        assertThat(section.toString()).doesNotContain("LIKELY_MISS").doesNotContain("risk");
    }

    @Test
    void aCaseTheMemberAlreadyChoseOnIsNotAWaitingOfferAndNoCaseIsPlainNothing() {
        when(reads.openHardshipCase(MEMBER)).thenReturn(Optional.of(
            new MemberCaseView(UUID.randomUUID(), LOAN, "PENDING_APPROVAL", TODAY, 15_000, false)));
        assertThat(sections(provider).hardship(CONTEXT).orElseThrow().offerWaiting()).isFalse();

        when(reads.openHardshipCase(MEMBER)).thenReturn(Optional.empty());
        HardshipSection none = sections(provider).hardship(CONTEXT).orElseThrow();
        assertThat(none.offerWaiting()).isFalse();
        assertThat(none.caseId()).isNull();
    }

    @Test
    void withoutPerMemberReadsLendingAndHardshipFailAndSavingsLeavesGoalsOut() {
        when(provider.getIfAvailable()).thenReturn(null);
        when(savings.getMemberSavingsSummary(MEMBER.toString()))
            .thenReturn(new MemberSavingsSummary(MEMBER.toString(), 4, 4, 0, 7_500, 30_000, true));
        FacadeHomeSections sections = sections(provider);

        assertThatThrownBy(() -> sections.lending(CONTEXT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sections.hardship(CONTEXT)).isInstanceOf(IllegalStateException.class);
        SavingsSection section = sections.savings(CONTEXT).orElseThrow();
        assertThat(section.goals()).isNull();
        assertThat(section.currentStreakMonths()).isEqualTo(4);
        assertThat(section.totalSaved().cents()).isEqualTo(30_000);
        assertThat(section.totalSaved().currency()).isEqualTo("USD");
    }

    @Test
    void savingsGoalsAreMappedAsCentsWithTheirCurrency() {
        when(savings.getMemberSavingsSummary(MEMBER.toString()))
            .thenReturn(new MemberSavingsSummary(MEMBER.toString(), 1, 1, 0, 7_500, 7_500, false));
        UUID goal = UUID.randomUUID();
        when(reads.goals(MEMBER)).thenReturn(List.of(new MemberReads.Goal(goal, "Stand mixer", "ACTIVE", 120_000, 7_500,
            7_500, "USD", LocalDate.of(2027, 6, 1))));

        SavingsSection section = sections(provider).savings(CONTEXT).orElseThrow();

        assertThat(section.goals()).singleElement().satisfies(g -> {
            assertThat(g.goalId()).isEqualTo(goal);
            assertThat(g.target().cents()).isEqualTo(120_000);
            assertThat(g.saved().cents()).isEqualTo(7_500);
            assertThat(g.targetDate()).isEqualTo(LocalDate.of(2027, 6, 1));
        });
    }

    @Test
    void theCreditRecordCarriesTheLevelSinceDateAndAHintInTheMembersLanguage() {
        RecordView record = new RecordView("BUILDING", 1, 150_000, 1, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 8, 1), 9_000,
            "85-94%", null, null, null, 0, 0, new NextStepView("STEADY", 3, 1, 8_500, false, false), "record-v1");
        when(creditRecord.record(MEMBER)).thenReturn(record);

        var english = sections(provider).creditRecord(CONTEXT).orElseThrow();
        var spanish = sections(provider).creditRecord(new HomeSections.Context(MEMBER, TODAY, "es")).orElseThrow();

        assertThat(english.level()).isEqualTo("BUILDING");
        assertThat(english.since()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(english.nextStep().nextLevel()).isEqualTo("STEADY");
        assertThat(english.nextStep().hint()).isEqualTo("To reach Steady: 3 more on-time saving months and 1 more loan repaid on plan.");
        assertThat(spanish.nextStep().hint()).startsWith("Para llegar a Steady");
    }

    @Test
    void theTopLevelHasNoNextStep() {
        when(creditRecord.record(MEMBER)).thenReturn(new RecordView("EXEMPLARY", 4, 750_000, 4, LocalDate.of(2024, 1, 1),
            LocalDate.of(2026, 1, 1), 9_900, "95-100%", null, null, null, 0, 0, null, "record-v1"));

        assertThat(sections(provider).creditRecord(CONTEXT).orElseThrow().nextStep()).isNull();
    }

    private static MemberApplicationView application(UUID id, String status) {
        return new MemberApplicationView(id, status, 120_000, 8, Instant.parse("2026-11-12T10:00:00Z"), null);
    }
}
