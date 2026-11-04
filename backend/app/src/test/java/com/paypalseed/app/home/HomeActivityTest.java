package com.paypalseed.app.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paypalseed.app.home.HomeView.ActivityItem;
import com.paypalseed.app.home.HomeView.ErrorCode;
import com.paypalseed.app.home.HomeView.Section;
import com.paypalseed.app.home.HomeView.SectionError;
import com.paypalseed.creditrecord.api.CreditRecordFacade;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.NextStepView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.RecordView;
import com.paypalseed.deskquery.api.DeskQueryFacade;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.MemberActivityItem;
import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The Home extras of the closing pass: recent activity (and that it fails alone), the next deposit date of a goal and the
 * months the next record level asks in all.
 */
class HomeActivityTest {

    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final HomeSections.Context CONTEXT = new HomeSections.Context(MEMBER, TODAY, "en");

    private DeskQueryFacade desk;
    private MemberReads reads;
    private CreditRecordFacade creditRecord;
    private SavingsFacade savings;

    @BeforeEach
    void setUp() {
        desk = mock(DeskQueryFacade.class);
        reads = mock(MemberReads.class);
        creditRecord = mock(CreditRecordFacade.class);
        savings = mock(SavingsFacade.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private FacadeHomeSections sections(int limit) {
        return new FacadeHomeSections(mock(IdentityFacade.class), savings, mock(PaymentsFacade.class), mock(UnderwritingFacade.class),
            creditRecord, provider(reads), provider(desk), limit);
    }

    // ---- recent activity ----

    @Test
    void activityIsMappedAsGivenWithCentsAndTheConfiguredLimit() {
        UUID goal = UUID.randomUUID();
        UUID loan = UUID.randomUUID();
        Instant at = Instant.parse("2026-11-03T17:00:00Z");
        when(desk.memberActivity(eq(MEMBER), anyInt())).thenReturn(List.of(
            new MemberActivityItem("DEPOSIT", 2_500, at, goal, null),
            new MemberActivityItem("COVER_APPLIED", 8_000, at.minusSeconds(3_600), null, loan)));

        List<ActivityItem> items = sections(7).recentActivity(CONTEXT).orElseThrow();

        verify(desk).memberActivity(MEMBER, 7);
        assertThat(items).containsExactly(
            new ActivityItem("DEPOSIT", HomeView.MoneyView.usd(2_500), at, goal, null),
            new ActivityItem("COVER_APPLIED", HomeView.MoneyView.usd(8_000), at.minusSeconds(3_600), null, loan));
    }

    @Test
    void theLimitIsHeldBetweenOneAndFifty() {
        sections(0).recentActivity(CONTEXT);
        sections(500).recentActivity(CONTEXT);

        verify(desk).memberActivity(MEMBER, 1);
        verify(desk).memberActivity(MEMBER, 50);
    }

    @Test
    void aMemberWithNoActivityGetsAnEmptyListAndNotAnError() {
        when(desk.memberActivity(eq(MEMBER), anyInt())).thenReturn(List.of());

        assertThat(sections(10).recentActivity(CONTEXT)).contains(List.of());
    }

    @Test
    void withoutAnActivitySourceTheListIsEmpty() {
        FacadeHomeSections none = new FacadeHomeSections(mock(IdentityFacade.class), savings, mock(PaymentsFacade.class),
            mock(UnderwritingFacade.class), creditRecord, provider(reads));

        assertThat(none.recentActivity(CONTEXT)).contains(List.of());
    }

    /** Every section answers except the one a test breaks. */
    private static class Fake implements HomeSections {
        Function<Context, Optional<List<ActivityItem>>> activity = c -> Optional.of(List.of());

        @Override
        public Optional<HomeView.ProfileSection> profile(UUID memberId) {
            return Optional.of(new HomeView.ProfileSection("Ana", "en", "PAYPAL"));
        }

        @Override
        public boolean payoutRecipientReady(UUID memberId) {
            return true;
        }

        @Override
        public Optional<HomeView.SavingsSection> savings(Context context) {
            return Optional.of(new HomeView.SavingsSection(1, HomeView.MoneyView.usd(0), HomeView.MoneyView.usd(0), 0, false, List.of()));
        }

        @Override
        public Optional<HomeView.LendingSection> lending(Context context) {
            return Optional.of(new HomeView.LendingSection(new HomeView.EligibilityCard(false, HomeView.MoneyView.usd(0),
                HomeView.MoneyView.usd(0), 3, 18, List.of()), null, null));
        }

        @Override
        public Optional<HomeView.UnderwritingSection> underwriting(Context context) {
            return Optional.of(new HomeView.UnderwritingSection(false, null));
        }

        @Override
        public Optional<HomeView.CreditRecordSection> creditRecord(Context context) {
            return Optional.of(new HomeView.CreditRecordSection("NEW", null, null));
        }

        @Override
        public Optional<HomeView.HardshipSection> hardship(Context context) {
            return Optional.of(new HomeView.HardshipSection(false, null, null, null));
        }

        @Override
        public Optional<List<ActivityItem>> recentActivity(Context context) {
            return activity.apply(context);
        }
    }

    private static MemberHomeService service(Fake sections) {
        return new MemberHomeService(sections, new FakeBusinessClock(TODAY), null);
    }

    @Test
    void theActivitySectionFailsAloneIntoSectionErrors() {
        Fake sections = new Fake();
        sections.activity = c -> {
            throw new IllegalStateException("the read model is down");
        };

        HomeView view = service(sections).home(MEMBER);

        assertThat(view.recentActivity()).isNull();
        assertThat(view.sectionErrors()).containsExactly(new SectionError(Section.recentActivity, ErrorCode.UNAVAILABLE));
        assertThat(view.savings()).isNotNull();
        assertThat(view.lending()).isNotNull();
        assertThat(view.creditRecord()).isNotNull();
        assertThat(view.hardship()).isNotNull();
    }

    @Test
    void aBrokenSectionDoesNotTakeTheActivityWithIt() {
        Fake sections = new Fake() {
            @Override
            public Optional<HomeView.LendingSection> lending(Context context) {
                throw new IllegalStateException("lending is down");
            }
        };
        ActivityItem item = new ActivityItem("DEPOSIT", HomeView.MoneyView.usd(2_500), Instant.parse("2026-11-03T17:00:00Z"), UUID.randomUUID(), null);
        sections.activity = c -> Optional.of(List.of(item));

        HomeView view = service(sections).home(MEMBER);

        assertThat(view.recentActivity()).containsExactly(item);
        assertThat(view.sectionErrors()).containsExactly(new SectionError(Section.lending, ErrorCode.UNAVAILABLE));
    }

    // ---- next deposit date ----

    private HomeView.GoalCard goalWithNextDeposit(LocalDate next) {
        when(savings.getMemberSavingsSummary(MEMBER.toString()))
            .thenReturn(new MemberSavingsSummary(MEMBER.toString(), 1, 1, 0, 7_500, 7_500, false));
        when(reads.goals(MEMBER)).thenReturn(List.of(new MemberReads.Goal(UUID.randomUUID(), "Mixer", "ACTIVE", 120_000, 7_500, 7_500,
            "USD", LocalDate.of(2027, 6, 1), next)));
        return sections(10).savings(CONTEXT).orElseThrow().goals().get(0);
    }

    @Test
    void aFutureOrTodaysDepositDateIsShown() {
        assertThat(goalWithNextDeposit(LocalDate.of(2026, 12, 3)).nextDepositDate()).isEqualTo(LocalDate.of(2026, 12, 3));
        assertThat(goalWithNextDeposit(TODAY).nextDepositDate()).isEqualTo(TODAY);
    }

    @Test
    void aDateThatHasPassedIsAReportNotYetRenewedAndIsLeftOut() {
        assertThat(goalWithNextDeposit(TODAY.minusDays(1)).nextDepositDate()).isNull();
        assertThat(goalWithNextDeposit(null).nextDepositDate()).isNull();
    }

    // ---- months the next level asks in all ----

    private HomeView.NextStep nextStepOf(String level, int monthsToGo) {
        when(creditRecord.record(MEMBER)).thenReturn(new RecordView("NEW", 0, 0, 0, null, null, 10_000, null, null, null, null, 0, 0,
            new NextStepView(level, monthsToGo, 0, 7_500, false, false), "record-v1"));
        return sections(10).creditRecord(CONTEXT).orElseThrow().nextStep();
    }

    @Test
    void theNextLevelsMonthsAreTheRulesOwnNumbersSoProgressNeedsNoRuleOfItsOwn() {
        assertThat(nextStepOf("BUILDING", 2).requiredOnTimeSavingMonths()).isEqualTo(3);
        assertThat(nextStepOf("STEADY", 4).requiredOnTimeSavingMonths()).isEqualTo(6);
        assertThat(nextStepOf("TRUSTED", 9).requiredOnTimeSavingMonths()).isEqualTo(9);
        assertThat(nextStepOf("EXEMPLARY", 0).requiredOnTimeSavingMonths()).isEqualTo(12);

        HomeView.NextStep step = nextStepOf("STEADY", 4);
        assertThat(step.onTimeSavingMonthsToGo()).isEqualTo(4);
        assertThat(step.requiredOnTimeSavingMonths() - step.onTimeSavingMonthsToGo()).as("months done so far").isEqualTo(2);
    }

    @Test
    void aLevelTheRulesDoNotKnowHasNoRequiredMonths() {
        assertThat(nextStepOf("LEGENDARY", 1).requiredOnTimeSavingMonths()).isNull();
    }
}
