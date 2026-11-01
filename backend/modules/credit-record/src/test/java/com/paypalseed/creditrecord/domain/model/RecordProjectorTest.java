package com.paypalseed.creditrecord.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.creditrecord.domain.model.RecordProjector.Applied;
import com.paypalseed.engines.rules.record.RecordLevel;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordProjectorTest {

    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID LOAN = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OTHER_LOAN = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private int keys;

    private RecordState fold(RecordFact... facts) {
        RecordState state = null;
        for (RecordFact f : facts) {
            state = RecordProjector.apply(state == null ? RecordState.initial(MEMBER, f.occurredOn()) : state, f).state();
        }
        return state;
    }

    private RecordFact deposit(int year, int month, int day) {
        return RecordFact.savingsDeposit("dep-" + keys++, MEMBER, LocalDate.of(year, month, day));
    }

    private static RecordFact paid(UUID loan, int seq, int daysLate, LocalDate on) {
        return RecordFact.instalmentPaid(MEMBER, loan, seq, daysLate, on);
    }

    private RecordFact missed(UUID loan, int seq, LocalDate on) {
        return RecordFact.instalmentMissed("miss-" + keys++, MEMBER, loan, seq, on);
    }

    @Test
    void threeSavingMonthsEarnBuildingAndTheLevelChangeIsReported() {
        RecordState s = fold(deposit(2026, 1, 5), deposit(2026, 2, 5));
        assertThat(s.level()).isEqualTo(RecordLevel.NEW);

        Applied third = RecordProjector.apply(s, deposit(2026, 3, 9));

        assertThat(third.levelChanged()).isTrue();
        assertThat(third.from()).isEqualTo(RecordLevel.NEW);
        assertThat(third.to()).isEqualTo(RecordLevel.BUILDING);
        assertThat(third.cause()).isEqualTo(RecordProjector.Cause.PROMOTION);
        assertThat(third.state().levelSince()).isEqualTo(LocalDate.of(2026, 3, 9));
        assertThat(third.state().since()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(third.state().lifetime().onTimeSavingMonths()).isEqualTo(3);
    }

    @Test
    void anotherDepositInTheSameMonthCountsOnce() {
        RecordState s = fold(deposit(2026, 1, 5), deposit(2026, 1, 20), deposit(2026, 1, 28));
        assertThat(s.lifetime().onTimeSavingMonths()).isEqualTo(1);
        assertThat(s.lifetime().missedSavingMonths()).isZero();
    }

    @Test
    void monthsWithoutADepositAreMissedWhenSavingResumes() {
        RecordState s = fold(deposit(2026, 1, 5), deposit(2026, 4, 5));
        assertThat(s.lifetime().onTimeSavingMonths()).isEqualTo(2);
        assertThat(s.lifetime().missedSavingMonths()).isEqualTo(2); // February and March
    }

    @Test
    void aLongPauseIsNotHeldAgainstTheMemberBeyondThreeMonths() {
        RecordState s = fold(deposit(2026, 1, 5), deposit(2026, 12, 5));
        assertThat(s.lifetime().missedSavingMonths()).isEqualTo(RecordProjector.MAX_CONSECUTIVE_MISSED_MONTHS);
    }

    @Test
    void closingAMonthCountsItOnceAndALaterDepositDoesNotCountItAgain() {
        RecordState s = fold(deposit(2026, 1, 5));
        s = RecordProjector.apply(s, RecordFact.savingMonthsClosed(MEMBER, LocalDate.of(2026, 3, 1))).state(); // Feb over
        assertThat(s.lifetime().missedSavingMonths()).isEqualTo(1);
        s = RecordProjector.apply(s, RecordFact.savingMonthsClosed(MEMBER, LocalDate.of(2026, 3, 20))).state(); // same close
        assertThat(s.lifetime().missedSavingMonths()).isEqualTo(1);
        s = RecordProjector.apply(s, deposit(2026, 3, 25)).state(); // March: nothing missed in between
        assertThat(s.lifetime().missedSavingMonths()).isEqualTo(1);
        assertThat(s.lifetime().onTimeSavingMonths()).isEqualTo(2);
    }

    @Test
    void closingNeverCountsAMemberWhoNeverSaved() {
        RecordState s = fold(RecordFact.loanDisbursed("l-1", MEMBER, LOAN, LocalDate.of(2026, 1, 1)));
        s = RecordProjector.apply(s, RecordFact.savingMonthsClosed(MEMBER, LocalDate.of(2026, 6, 1))).state();
        assertThat(s.lifetime().missedSavingMonths()).isZero();
    }

    @Test
    void anInstalmentPaidInsideGraceIsOnTimeAndOneAfterGraceIsLate() {
        RecordState s = fold(paid(LOAN, 1, 0, LocalDate.of(2026, 5, 1)), paid(LOAN, 2, 5, LocalDate.of(2026, 6, 5)),
                paid(LOAN, 3, 6, LocalDate.of(2026, 7, 6)));
        assertThat(s.lifetime().instalmentsOnTime()).isEqualTo(2);
        assertThat(s.lifetime().instalmentsLate()).isEqualTo(1);
    }

    @Test
    void theSameInstalmentIsCountedOnce() {
        RecordState s = fold(paid(LOAN, 1, 0, LocalDate.of(2026, 5, 1)), paid(LOAN, 1, 0, LocalDate.of(2026, 5, 2)));
        assertThat(s.lifetime().instalmentsOnTime()).isEqualTo(1);
    }

    @Test
    void aMissedInstalmentBlocksPromotionUntilItIsPaidAndNeverLowersALevel() {
        RecordState s = fold(deposit(2026, 1, 5), deposit(2026, 2, 5), deposit(2026, 3, 5));
        assertThat(s.level()).isEqualTo(RecordLevel.BUILDING);
        s = RecordProjector.apply(s, missed(LOAN, 1, LocalDate.of(2026, 4, 1))).state();
        assertThat(s.level()).isEqualTo(RecordLevel.BUILDING);
        assertThat(s.openLate()).hasSize(1);
        assertThat(s.lifetime().openLateInstalments()).isEqualTo(1);

        s = RecordProjector.apply(s, paid(LOAN, 1, 12, LocalDate.of(2026, 4, 12))).state();
        assertThat(s.openLate()).isEmpty();
        assertThat(s.lifetime().instalmentsLate()).isEqualTo(1);
        assertThat(s.level()).isEqualTo(RecordLevel.BUILDING);
    }

    @Test
    void aPaymentThatArrivesBeforeTheMissItSettlesLeavesNothingOpen() {
        RecordState s = fold(paid(LOAN, 2, 9, LocalDate.of(2026, 5, 10)), missed(LOAN, 2, LocalDate.of(2026, 5, 7)));
        assertThat(s.openLate()).isEmpty();
        assertThat(s.lifetime().openLateInstalments()).isZero();
    }

    @Test
    void aLoanRepaidWithNoMissIsOnPlanAndOneWithMissesIsNot() {
        RecordState s = fold(RecordFact.loanRepaid(MEMBER, LOAN, 0, LocalDate.of(2026, 9, 1)),
                RecordFact.loanRepaid(MEMBER, OTHER_LOAN, 2, LocalDate.of(2026, 10, 1)));
        assertThat(s.lifetime().loansRepaidOnPlan()).isEqualTo(1);
        assertThat(s.lifetime().loansRepaidOffPlan()).isEqualTo(1);
    }

    @Test
    void aHardshipPlanIsOnPlanAndChangesNeitherTheCountersNorTheLevel() {
        RecordState before = fold(deposit(2026, 1, 5), deposit(2026, 2, 5), deposit(2026, 3, 5),
                paid(LOAN, 1, 0, LocalDate.of(2026, 4, 1)));
        Applied applied = RecordProjector.apply(before, RecordFact.hardshipPlanApplied("h-1", MEMBER, UUID.randomUUID(), LOAN,
                LocalDate.of(2026, 5, 1)));

        assertThat(applied.levelChanged()).isFalse();
        assertThat(applied.state().hardshipPlans()).isEqualTo(1);
        assertThat(applied.state().lifetime()).isEqualTo(before.lifetime());
        assertThat(applied.state().current()).isEqualTo(before.current());
        assertThat(applied.state().openLate()).isEqualTo(before.openLate());
        assertThat(applied.state().defaults()).isZero();
    }

    @Test
    void anInstalmentPaidOnTimeAgainstItsNewDateAfterAHardshipPlanStillCountsAsOnTime() {
        RecordState s = fold(RecordFact.hardshipPlanApplied("h-1", MEMBER, UUID.randomUUID(), LOAN, LocalDate.of(2026, 5, 1)),
                paid(LOAN, 3, 0, LocalDate.of(2026, 6, 20)));
        assertThat(s.lifetime().instalmentsOnTime()).isEqualTo(1);
        assertThat(s.lifetime().instalmentsLate()).isZero();
    }

    @Test
    void aDefaultIsTheOnlyThingThatLowersALevelAndTheClimbStartsOver() {
        RecordState s = fold(deposit(2026, 1, 5), deposit(2026, 2, 5), deposit(2026, 3, 5));
        s = RecordProjector.apply(s, missed(LOAN, 1, LocalDate.of(2026, 4, 1))).state();
        assertThat(s.level()).isEqualTo(RecordLevel.BUILDING);

        Applied defaulted = RecordProjector.apply(s, RecordFact.loanDefaulted(MEMBER, LOAN, LocalDate.of(2026, 4, 10)));

        assertThat(defaulted.to()).isEqualTo(RecordLevel.NEW);
        assertThat(defaulted.cause()).isEqualTo(RecordProjector.Cause.DEFAULT);
        assertThat(defaulted.state().defaults()).isEqualTo(1);
        assertThat(defaulted.state().openLate()).isEmpty();
        assertThat(defaulted.state().current().onTimeSavingMonths()).isZero();
        assertThat(defaulted.state().lifetime().onTimeSavingMonths()).isEqualTo(3); // history stays true

        RecordState again = fold2(defaulted.state(), deposit(2026, 4, 15), deposit(2026, 5, 5), deposit(2026, 6, 5));
        assertThat(again.level()).isEqualTo(RecordLevel.BUILDING);
    }

    @Test
    void aMissThatArrivesAfterItsLoansDefaultIsNotAFreshDebt() {
        RecordState s = fold(deposit(2026, 1, 5), RecordFact.loanDefaulted(MEMBER, LOAN, LocalDate.of(2026, 5, 1)),
                missed(LOAN, 3, LocalDate.of(2026, 5, 1)));
        assertThat(s.openLate()).isEmpty();
        // Another loan's miss is still a miss.
        s = RecordProjector.apply(s, missed(OTHER_LOAN, 1, LocalDate.of(2026, 6, 1))).state();
        assertThat(s.openLate()).hasSize(1);
    }

    @Test
    void aLevelIsKeptWhenMissedInstalmentsAndMonthsPileUp() {
        RecordState s = fold(deposit(2026, 1, 1), deposit(2026, 2, 1), deposit(2026, 3, 1));
        for (int i = 1; i <= 5; i++) {
            s = RecordProjector.apply(s, missed(LOAN, i, LocalDate.of(2026, 4, i))).state();
        }
        s = RecordProjector.apply(s, RecordFact.savingMonthsClosed(MEMBER, LocalDate.of(2026, 12, 1))).state();
        assertThat(s.level()).isEqualTo(RecordLevel.BUILDING);
    }

    @Test
    void replayingTheSameFactsRebuildsTheSameState() {
        List<RecordFact> facts = List.of(deposit(2026, 1, 5), RecordFact.loanDisbursed("l-1", MEMBER, LOAN, LocalDate.of(2026, 1, 9)),
                deposit(2026, 2, 5), paid(LOAN, 1, 2, LocalDate.of(2026, 2, 11)), missed(LOAN, 2, LocalDate.of(2026, 3, 20)),
                deposit(2026, 3, 5), paid(LOAN, 2, 14, LocalDate.of(2026, 3, 28)),
                RecordFact.loanRepaid(MEMBER, LOAN, 1, LocalDate.of(2026, 4, 2)));

        assertThat(fold(facts.toArray(RecordFact[]::new))).isEqualTo(fold(facts.toArray(RecordFact[]::new)));
    }

    private static RecordState fold2(RecordState start, RecordFact... facts) {
        RecordState s = start;
        for (RecordFact f : facts) {
            s = RecordProjector.apply(s, f).state();
        }
        return s;
    }
}
