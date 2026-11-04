package com.paypalseed.deskquery.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.deskquery.DbFixture;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.MemberActivityItem;
import com.paypalseed.deskquery.infrastructure.persistence.ActivityRepository;
import com.paypalseed.deskquery.infrastructure.persistence.ProjectionResetRepository;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Projection;
import com.paypalseed.lending.api.events.LendingEvents.InstalmentView;
import com.paypalseed.lending.api.events.LendingEvents.LoanVoided;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReceived;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReversed;
import com.paypalseed.lending.api.events.LendingEvents.ScheduleGenerated;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.events.DepositReceivedEvent;
import com.paypalseed.savings.api.events.WithdrawalProcessedEvent;
import com.paypalseed.savings.api.events.WithdrawalReturnedEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The member's activity list against a real database: what each fact becomes, that repeats change nothing, and whose it is. */
class ActivityProjectorTest {

    private static final UUID ANA = new UUID(7, 1);
    private static final UUID BO = new UUID(7, 2);
    private static final UUID GOAL = new UUID(8, 1);
    private static final UUID LOAN = new UUID(9, 1);
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    /** Midnight of {@link #DAY} in Los Angeles (UTC-8 in November). */
    private static final Instant DAY_START = Instant.parse("2026-11-10T08:00:00Z");

    private static ActivityProjector projector;
    private static DeskQueryFacadeService facade;

    @BeforeAll
    static void wire() {
        var dsl = DbFixture.dsl();
        ActivityRepository repository = new ActivityRepository(dsl);
        projector = new ActivityProjector(repository);
        facade = new DeskQueryFacadeService(new ProjectionResetRepository(dsl), null, repository, null);
    }

    @BeforeEach
    void clean() {
        DbFixture.clean();
    }

    private static List<MemberActivityItem> activity(UUID member) {
        return facade.memberActivity(member, 50);
    }

    @Test
    void aDepositIsOneRowWhateverNumberOfTimesItIsDelivered() {
        DepositReceivedEvent deposit = new DepositReceivedEvent(Ids.newId(), ANA.toString(), GOAL, Money.usd(75), "CAP-1",
                Instant.parse("2026-11-03T17:00:00Z"));

        projector.on(deposit);
        projector.on(deposit);
        // the same PayPal payment under another event id (a replay after a restart) is the same fact
        projector.on(new DepositReceivedEvent(Ids.newId(), ANA.toString(), GOAL, Money.usd(75), "CAP-1", Instant.parse("2026-11-03T17:00:01Z")));

        assertThat(activity(ANA)).containsExactly(new MemberActivityItem("DEPOSIT", 7_500, Instant.parse("2026-11-03T17:00:00Z"), GOAL, null));
    }

    @Test
    void savingsKindsCarryTheGoalAndTheTimeOfTheEvent() {
        Instant at = Instant.parse("2026-11-04T10:00:00Z");
        projector.on(new WithdrawalProcessedEvent(Ids.newId(), ANA.toString(), GOAL, Money.usd(20), "PAYOUT-1", at));
        UUID withdrawal = Ids.newId();
        projector.on(new WithdrawalReturnedEvent(Ids.newId(), ANA.toString(), GOAL, withdrawal, Money.usd(20), "RETURNED", at.plusSeconds(60)));
        projector.on(new WithdrawalReturnedEvent(Ids.newId(), ANA.toString(), GOAL, withdrawal, Money.usd(20), "RETURNED", at.plusSeconds(60)));

        assertThat(activity(ANA)).containsExactly(
                new MemberActivityItem("WITHDRAWAL_RETURNED", 2_000, at.plusSeconds(60), GOAL, null),
                new MemberActivityItem("WITHDRAWAL", 2_000, at, GOAL, null));
    }

    @Test
    void aLoanPayoutIsTheTotalOfItsFirstScheduleAndLaterVersionsSayNothingNew() {
        List<InstalmentView> schedule = List.of(new InstalmentView(1, DAY.plusMonths(1), 15_000), new InstalmentView(2, DAY.plusMonths(2), 15_000));
        ScheduleGenerated first = new ScheduleGenerated(Ids.newId(), LOAN, ANA, DAY, 1, schedule);

        projector.on(first);
        projector.on(first);
        projector.on(new ScheduleGenerated(Ids.newId(), LOAN, ANA, DAY.plusDays(9), 2, schedule));

        assertThat(activity(ANA)).containsExactly(new MemberActivityItem("LOAN_DISBURSED", 30_000, DAY_START, null, LOAN));
    }

    @Test
    void aPaymentAndAPaymentTheSafetyFundMadeAreToldApartAndAReversalIsItsOwnRow() {
        UUID paid = Ids.newId();
        projector.on(new RepaymentReceived(Ids.newId(), LOAN, ANA, DAY, paid, "CHECKOUT", 5_000, 5_000, 0, 25_000, "ACTIVE"));
        UUID cover = Ids.newId();
        // the fund's money counts as far as the loan could take it
        projector.on(new RepaymentReceived(Ids.newId(), LOAN, ANA, DAY.plusDays(1), cover, "RESERVE", 9_000, 8_000, 1_000, 17_000, "ACTIVE"));
        projector.on(new RepaymentReversed(Ids.newId(), LOAN, ANA, DAY.plusDays(2), Ids.newId(), paid, 5_000, 22_000));

        assertThat(activity(ANA)).extracting(MemberActivityItem::kind, MemberActivityItem::amountCents)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("LOAN_PAYMENT_REVERSED", 5_000L),
                        org.assertj.core.groups.Tuple.tuple("COVER_APPLIED", 8_000L),
                        org.assertj.core.groups.Tuple.tuple("LOAN_PAYMENT", 5_000L));
        assertThat(activity(ANA)).allSatisfy(a -> assertThat(a.loanId()).isEqualTo(LOAN));
    }

    @Test
    void aVoidedLoanIsOneRowWithTheMoneyThatCameBack() {
        LoanVoided voided = new LoanVoided(Ids.newId(), LOAN, ANA, DAY, Ids.newId(), "REF-1", "RETURNED", 120_000, 0, 0);

        projector.on(voided);
        projector.on(voided);

        assertThat(activity(ANA)).containsExactly(new MemberActivityItem("LOAN_VOIDED", 120_000, DAY_START, null, LOAN));
    }

    @Test
    void aSourceThatGivesOnlyADateIsDatedAtTheStartOfThatBusinessDayInTheProgramZone() {
        projector.on(new RepaymentReceived(Ids.newId(), LOAN, ANA, LocalDate.of(2026, 7, 4), Ids.newId(), "AUTOPAY", 100, 100, 0, 0, "REPAID"));

        // July is daylight saving time: midnight in Los Angeles is 07:00 UTC.
        assertThat(activity(ANA)).singleElement().extracting(MemberActivityItem::occurredAt).isEqualTo(Instant.parse("2026-07-04T07:00:00Z"));
    }

    @Test
    void theListIsNewestFirstLimitedAndOnlyTheMembersOwn() {
        for (int i = 1; i <= 12; i++) {
            projector.on(new DepositReceivedEvent(Ids.newId(), ANA.toString(), GOAL, Money.usd(i), "CAP-" + i,
                    Instant.parse("2026-10-01T10:00:00Z").plusSeconds(i * 3_600L)));
        }
        projector.on(new DepositReceivedEvent(Ids.newId(), BO.toString(), Ids.newId(), Money.usd(99), "CAP-BO", Instant.parse("2026-10-30T10:00:00Z")));

        List<MemberActivityItem> latest = facade.memberActivity(ANA, 10);

        assertThat(latest).hasSize(10);
        assertThat(latest).extracting(MemberActivityItem::amountCents).startsWith(1_200L, 1_100L).endsWith(300L);
        assertThat(activity(BO)).extracting(MemberActivityItem::amountCents).containsExactly(9_900L);
        assertThat(activity(new UUID(7, 3))).isEmpty();
    }

    @Test
    void sameTimeKeepsOneOrderOnEveryRead() {
        Instant same = Instant.parse("2026-10-01T10:00:00Z");
        projector.on(new DepositReceivedEvent(Ids.newId(), ANA.toString(), GOAL, Money.usd(1), "CAP-A", same));
        projector.on(new DepositReceivedEvent(Ids.newId(), ANA.toString(), GOAL, Money.usd(2), "CAP-B", same));

        assertThat(activity(ANA)).isEqualTo(activity(ANA));
        assertThat(activity(ANA)).extracting(MemberActivityItem::amountCents).containsExactly(200L, 100L);
    }

    @Test
    void aLimitBelowOneIsRefusedAndTheActivityResetEmptiesOnlyTheActivity() {
        projector.on(new DepositReceivedEvent(Ids.newId(), ANA.toString(), GOAL, Money.usd(1), "CAP-A", Instant.parse("2026-10-01T10:00:00Z")));
        assertThatThrownBy(() -> facade.memberActivity(ANA, 0)).isInstanceOf(IllegalArgumentException.class);

        assertThat(facade.reset(Projection.ACTIVITY)).isEqualTo(1);

        assertThat(activity(ANA)).isEmpty();
    }
}
