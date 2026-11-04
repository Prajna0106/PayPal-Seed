package com.paypalseed.deskquery.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paypalseed.deskquery.DbFixture;
import com.paypalseed.deskquery.domain.model.MemberRefs;
import com.paypalseed.deskquery.infrastructure.persistence.ActivityRepository;
import com.paypalseed.deskquery.infrastructure.persistence.ApplicationViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.RebuildMarkerRepository;
import com.paypalseed.deskquery.infrastructure.persistence.SavingsViewRepository;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.DepositFact;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The rebuild of saving months in the business zone (ADR 0027): a deposit on the evening of a month's last day, stored
 * under the old UTC rule as the next month, lands in its own month, and the streak and the activity list follow.
 */
class SavingMonthsRebuildTest {

    private static final String ANA = "00000000-0000-0000-0000-0000000000a1";
    private static final String BEA = "00000000-0000-0000-0000-0000000000b2";
    private static final UUID GOAL = new UUID(5, 1);

    private static DSLContext dsl;
    private static SavingsViewRepository savingsView;
    private static ActivityRepository activityRepository;
    private static RebuildMarkerRepository markers;
    private static ApplicationProjector applications;
    private SavingsFacade savings;
    private SavingMonthsRebuild rebuild;

    @BeforeAll
    static void wire() {
        dsl = DbFixture.dsl();
        savingsView = new SavingsViewRepository(dsl);
        activityRepository = new ActivityRepository(dsl);
        markers = new RebuildMarkerRepository(dsl);
        applications = new ApplicationProjector(new ApplicationViewRepository(dsl),
                new DeskQuerySettings(Duration.ofHours(24), Duration.ofHours(4), 50, 200, 10_000, ""));
    }

    @BeforeEach
    void clean() {
        DbFixture.clean();
        savings = mock(SavingsFacade.class);
        // The statements run on their own connections here; the transaction itself is the integration test's business.
        rebuild = new SavingMonthsRebuild(savings, savingsView, new ApplicationViewRepository(dsl),
                new ActivityProjector(activityRepository), markers, mock(PlatformTransactionManager.class));
    }

    private static DepositFact deposit(String member, String key, String at) {
        return new DepositFact(key, member, GOAL, 2_500, Instant.parse(at));
    }

    private void savingsHolds(String member, DepositFact... facts) {
        when(savings.membersWithDeposits(any(), anyInt())).thenAnswer(call -> call.getArgument(0) == null ? List.of(member) : List.of());
        when(savings.depositsOfMember(member)).thenReturn(List.of(facts));
    }

    private List<LocalDate> storedMonths(String member) {
        return dsl.select(com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.MEMBER_SAVINGS_MONTH.MONTH)
                .from(com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.MEMBER_SAVINGS_MONTH)
                .where(com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.MEMBER_SAVINGS_MONTH.MEMBER_REF.eq(MemberRefs.ofSavings(member)))
                .orderBy(com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.MEMBER_SAVINGS_MONTH.MONTH)
                .fetch(com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.MEMBER_SAVINGS_MONTH.MONTH);
    }

    private int streakOf(String member) {
        return dsl.fetchOne("select streak_months from deskquery.member_savings where member_ref = ?", MemberRefs.ofSavings(member))
                .get(0, Integer.class);
    }

    @Test
    void aDepositNearTheUtcMonthBoundaryLandsInTheBusinessMonthAfterTheRebuild() {
        UUID ana = MemberRefs.ofSavings(ANA);
        // 06:30 UTC on 1 November is 23:30 on 31 October in Los Angeles; the old rule counted it for November.
        assertThat(Instant.parse("2026-11-01T06:30:00Z").atZone(java.time.ZoneOffset.UTC).toLocalDate().getMonthValue()).isEqualTo(11);
        savingsView.addMonth(ana, LocalDate.of(2026, 9, 1));
        savingsView.addMonth(ana, LocalDate.of(2026, 11, 1));
        savingsView.setStreak(ana, 1);
        applications.apply(new ApplicationProjector.Snapshot(new UUID(6, 1), ana, "SUBMITTED", 50_000, 6,
                Instant.parse("2026-11-02T10:00:00Z"), null, Instant.parse("2026-11-02T10:00:00Z")));
        savingsHolds(ANA, deposit(ANA, "T-SEP", "2026-09-15T17:00:00Z"), deposit(ANA, "T-OCT", "2026-11-01T06:30:00Z"),
                deposit(ANA, "T-NOV", "2026-11-20T17:00:00Z"));

        assertThat(rebuild.rebuildAll()).isEqualTo(1);

        assertThat(storedMonths(ANA)).containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1));
        assertThat(streakOf(ANA)).isEqualTo(3);
        assertThat(dsl.fetchOne("select saving_streak_months from deskquery.application_view where member_ref = ?", ana).get(0, Integer.class))
                .as("the queue shows the rebuilt streak").isEqualTo(3);
    }

    @Test
    void aDepositJustAfterMidnightInLosAngelesBelongsToTheNewMonthAndOneJustBeforeToTheOld() {
        savingsHolds(ANA, deposit(ANA, "T-1", "2026-12-01T07:59:59Z"), deposit(ANA, "T-2", "2026-12-01T08:00:00Z"));

        rebuild.rebuildAll();

        // 07:59:59 UTC is 23:59:59 on 30 November (UTC-8); 08:00:00 UTC is midnight on 1 December.
        assertThat(storedMonths(ANA)).containsExactly(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1));
    }

    @Test
    void rebuildingTwiceGivesTheSameMonthsAndOneActivityRowPerDeposit() {
        savingsHolds(ANA, deposit(ANA, "T-1", "2026-10-05T17:00:00Z"), deposit(ANA, "T-2", "2026-11-05T17:00:00Z"));

        rebuild.rebuildAll();
        rebuild.rebuildAll();

        assertThat(storedMonths(ANA)).containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1));
        assertThat(streakOf(ANA)).isEqualTo(2);
        assertThat(activityRepository.newest(MemberRefs.ofSavings(ANA), 10)).hasSize(2)
                .allSatisfy(a -> assertThat(a.kind()).isEqualTo("DEPOSIT"));
        // the key a live DepositReceivedEvent uses is the same, so the deposit is never listed twice
        assertThat(dsl.fetchCount(com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.MEMBER_ACTIVITY,
                com.paypalseed.deskquery.infrastructure.persistence.jooq.Tables.MEMBER_ACTIVITY.ACTIVITY_KEY.eq(ActivityProjector.depositKey("T-1"))))
                .isEqualTo(1);
    }

    @Test
    void aMemberWithNoCreditedDepositIsLeftAsStored() {
        UUID bea = MemberRefs.ofSavings(BEA);
        savingsView.addMonth(bea, LocalDate.of(2026, 8, 1));
        savingsView.setStreak(bea, 1);
        savingsHolds(ANA, deposit(ANA, "T-1", "2026-10-05T17:00:00Z"));
        when(savings.membersWithDeposits(any(), anyInt())).thenAnswer(call -> call.getArgument(0) == null ? List.of(ANA, BEA) : List.of());
        when(savings.depositsOfMember(BEA)).thenReturn(List.of());

        assertThat(rebuild.rebuildAll()).as("only the member with deposits is rebuilt").isEqualTo(1);

        assertThat(storedMonths(BEA)).containsExactly(LocalDate.of(2026, 8, 1));
        assertThat(streakOf(BEA)).isEqualTo(1);
    }

    @Test
    void everyPageOfMembersIsRebuilt() {
        when(savings.membersWithDeposits(isNull(), anyInt())).thenReturn(List.of(ANA));
        when(savings.membersWithDeposits(eq(ANA), anyInt())).thenReturn(List.of(BEA));
        when(savings.membersWithDeposits(eq(BEA), anyInt())).thenReturn(List.of());
        when(savings.depositsOfMember(ANA)).thenReturn(List.of(deposit(ANA, "T-A", "2026-10-05T17:00:00Z")));
        when(savings.depositsOfMember(BEA)).thenReturn(List.of(deposit(BEA, "T-B", "2026-11-05T17:00:00Z")));

        assertThat(rebuild.rebuildAll()).isEqualTo(2);

        assertThat(storedMonths(ANA)).containsExactly(LocalDate.of(2026, 10, 1));
        assertThat(storedMonths(BEA)).containsExactly(LocalDate.of(2026, 11, 1));
    }

    @Test
    void theStartUpRunHappensOnceAndOnlyAfterEveryMemberWasRebuilt() {
        savingsHolds(ANA, deposit(ANA, "T-1", "2026-10-05T17:00:00Z"));
        assertThat(markers.isDone(SavingMonthsRebuild.NAME)).isFalse();

        assertThat(rebuild.runOnce()).isTrue();
        assertThat(markers.isDone(SavingMonthsRebuild.NAME)).isTrue();
        assertThat(rebuild.runOnce()).as("the marker says it was done").isFalse();
    }

    @Test
    void aRunThatFailsWritesNoMarkerSoTheNextStartTriesAgain() {
        when(savings.membersWithDeposits(any(), anyInt())).thenThrow(new IllegalStateException("savings is down"));

        assertThatThrownBy(rebuild::runOnce).isInstanceOf(IllegalStateException.class);

        assertThat(markers.isDone(SavingMonthsRebuild.NAME)).isFalse();
    }
}
