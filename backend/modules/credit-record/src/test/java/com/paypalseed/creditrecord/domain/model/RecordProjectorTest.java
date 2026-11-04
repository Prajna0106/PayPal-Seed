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
    // ---- help from the safety fund (ADR 0025) ----------------------------------------------------------------------------

    private static RecordFact fund(UUID loan, int seq, LocalDate on) {
        return RecordFact.instalmentFundCovered(MEMBER, loan, seq, on);
    }

    private static final LocalDate D = LocalDate.of(2026, 10, 10);

    @Test
    void anInstalmentTheFundPaidCountsNeitherOnTimeNorLateAndLeavesNothingOpen() {
        RecordState s = fold(missed(LOAN, 1, D), fund(LOAN, 1, D), paid(LOAN, 1, 40, D), paid(LOAN, 2, 0, D));

        assertThat(s.lifetime().instalmentsOnTime()).isEqualTo(1); // only the member's own seq 2
        assertThat(s.lifetime().instalmentsLate()).isZero();
        assertThat(s.lifetime().openLateInstalments()).isZero();
        assertThat(s.current().instalmentsOnTime()).isEqualTo(1);
        assertThat(s.current().instalmentsLate()).isZero();
    }

    @Test
    void theFundsPartIsTakenBackWhateverOrderTheFactsArrive() {
        RecordFact miss = missed(LOAN, 1, D);
        RecordFact cover = fund(LOAN, 1, D);
        RecordFact lateOwn = paid(LOAN, 1, 20, D);
        RecordFact onTimeOwn = paid(LOAN, 2, 0, D);
        RecordFact[][] orders = {{miss, cover, lateOwn, onTimeOwn}, {onTimeOwn, lateOwn, cover, miss},
                {cover, lateOwn, miss, onTimeOwn}, {lateOwn, onTimeOwn, miss, cover}, {miss, lateOwn, onTimeOwn, cover}};
        for (RecordFact[] order : orders) {
            RecordState s = fold(order);
            assertThat(s.lifetime().instalmentsOnTime()).as("on time").isEqualTo(1);
            assertThat(s.lifetime().instalmentsLate()).as("late").isZero();
            assertThat(s.lifetime().openLateInstalments()).as("open").isZero();
        }
    }

    @Test
    void aMissTheFundCoveredDoesNotMakeTheLoanOffPlan() {
        RecordState s = fold(missed(LOAN, 1, D), fund(LOAN, 1, D), paid(LOAN, 1, 0, D), paid(LOAN, 2, 0, D), paid(LOAN, 3, 0, D),
                RecordFact.loanRepaid(MEMBER, LOAN, 1, D));

        assertThat(s.lifetime().loansRepaidOnPlan()).isEqualTo(1);
        assertThat(s.lifetime().loansRepaidOffPlan()).isZero();
        assertThat(s.loansCompletedWithFundHelp()).isZero();
        assertThat(s.lifetime().instalmentsOnTime()).isEqualTo(2);
    }

    @Test
    void aMissTheMemberOwnsStillMakesTheLoanOffPlanWhenTheFundCoveredAnother() {
        RecordState s = fold(missed(LOAN, 1, D), missed(LOAN, 2, D), fund(LOAN, 1, D), paid(LOAN, 1, 0, D), paid(LOAN, 2, 30, D),
                RecordFact.loanRepaid(MEMBER, LOAN, 2, D));

        assertThat(s.lifetime().loansRepaidOnPlan()).isZero();
        assertThat(s.lifetime().loansRepaidOffPlan()).isEqualTo(1);
        assertThat(s.lifetime().instalmentsLate()).isEqualTo(1);
    }

    @Test
    void aLoanTheFundPaidInFullIsRepaidButNotCompletedByTheMember() {
        RecordState s = fold(fund(LOAN, 1, D), fund(LOAN, 2, D), paid(LOAN, 1, 0, D), paid(LOAN, 2, 0, D),
                RecordFact.loanRepaid(MEMBER, LOAN, 0, D));

        assertThat(s.loansCompletedWithFundHelp()).isEqualTo(1);
        assertThat(s.lifetime().loansRepaidOnPlan()).isZero();
        assertThat(s.lifetime().loansRepaidOffPlan()).isZero();
        assertThat(s.lifetime().instalmentsOnTime()).isZero();
        assertThat(s.lifetime().instalmentsLate()).isZero();
        assertThat(s.defaults()).isZero();
    }

    @Test
    void aLoanWithBothTheMembersAndTheFundsInstalmentsIsTheMembersLoan() {
        RecordState s = fold(fund(LOAN, 2, D), paid(LOAN, 1, 0, D), paid(LOAN, 2, 0, D), RecordFact.loanRepaid(MEMBER, LOAN, 0, D));

        assertThat(s.loansCompletedWithFundHelp()).isZero();
        assertThat(s.lifetime().loansRepaidOnPlan()).isEqualTo(1);
        assertThat(s.lifetime().instalmentsOnTime()).isEqualTo(1);
    }

    @Test
    void theOutcomeOfAFundPaidLoanDoesNotDependOnTheOrderTheFactsArrive() {
        RecordFact cover1 = fund(LOAN, 1, D);
        RecordFact cover2 = fund(LOAN, 2, D);
        RecordFact paid1 = paid(LOAN, 1, 0, D);
        RecordFact paid2 = paid(LOAN, 2, 0, D);
        RecordFact repaid = RecordFact.loanRepaid(MEMBER, LOAN, 0, D);
        RecordFact[][] orders = {{cover1, cover2, paid1, paid2, repaid}, {paid1, paid2, repaid, cover1, cover2},
                {repaid, paid2, cover2, paid1, cover1}, {paid1, cover1, repaid, paid2, cover2}};
        for (RecordFact[] order : orders) {
            RecordState s = fold(order);
            assertThat(s.loansCompletedWithFundHelp()).isEqualTo(1);
            assertThat(s.lifetime().loansRepaidOnPlan()).isZero();
            assertThat(s.lifetime().loansRepaidOffPlan()).isZero();
            assertThat(s.lifetime().instalmentsOnTime()).isZero();
            assertThat(s.current().loansRepaidOnPlan()).isZero();
        }
    }

    @Test
    void aFundPaidLoanDoesNotLiftTheLevelButTheMembersOwnLoanDoes() {
        RecordFact[] savingSixMonths = {deposit(2026, 1, 5), deposit(2026, 2, 5), deposit(2026, 3, 5), deposit(2026, 4, 5),
                deposit(2026, 5, 5), deposit(2026, 6, 5)};
        RecordState fundLoan = fold(concat(savingSixMonths, fund(LOAN, 1, D), paid(LOAN, 1, 0, D), RecordFact.loanRepaid(MEMBER, LOAN, 0, D)));
        RecordState ownLoan = fold(concat(savingSixMonths, paid(OTHER_LOAN, 1, 0, D), RecordFact.loanRepaid(MEMBER, OTHER_LOAN, 0, D)));

        assertThat(fundLoan.level()).isEqualTo(RecordLevel.BUILDING);
        assertThat(ownLoan.level()).isEqualTo(RecordLevel.STEADY);
    }

    @Test
    void helpFromTheFundNeverLowersAnExistingLevelOrCountsAsADefault() {
        RecordState before = fold(deposit(2026, 1, 5), deposit(2026, 2, 5), deposit(2026, 3, 5));
        RecordState after = RecordProjector.apply(RecordProjector.apply(before, missed(LOAN, 1, D)).state(), fund(LOAN, 1, D)).state();

        assertThat(before.level()).isEqualTo(RecordLevel.BUILDING);
        assertThat(after.level()).isEqualTo(RecordLevel.BUILDING);
        assertThat(after.defaults()).isZero();
        assertThat(after.lifetime().openLateInstalments()).isZero();
        assertThat(after.lifetime().instalmentsLate()).isZero();
    }

    @Test
    void theSameFundFactTwiceChangesNothingAndReplayRebuildsTheSameState() {
        RecordFact[] facts = {missed(LOAN, 1, D), fund(LOAN, 1, D), paid(LOAN, 1, 12, D), paid(LOAN, 2, 0, D),
                RecordFact.loanRepaid(MEMBER, LOAN, 1, D)};
        RecordState once = fold(facts);
        RecordState twice = fold(concat(facts, fund(LOAN, 1, D)));

        assertThat(twice.lifetime()).isEqualTo(once.lifetime());
        assertThat(twice.current()).isEqualTo(once.current());
        assertThat(twice.settled()).isEqualTo(once.settled());
        assertThat(twice.level()).isEqualTo(once.level());
        assertThat(fold(facts)).isEqualTo(once);
    }

    @Test
    void aRiseOnALoanTheFundTurnsOutToHavePaidIsTakenBackWhenItsFactArrivesLate() {
        RecordState s = fold(deposit(2026, 1, 5), deposit(2026, 2, 5), deposit(2026, 3, 5), deposit(2026, 4, 5),
                deposit(2026, 5, 5), deposit(2026, 6, 5), paid(LOAN, 1, 0, D), paid(LOAN, 2, 0, D));
        Applied repaid = RecordProjector.apply(s, RecordFact.loanRepaid(MEMBER, LOAN, 0, D));
        assertThat(repaid.to()).as("counted as the member's until the fund's part is known").isEqualTo(RecordLevel.STEADY);

        Applied first = RecordProjector.apply(repaid.state(), fund(LOAN, 1, D));
        assertThat(first.levelChanged()).as("the member still paid instalment 2").isFalse();
        assertThat(first.state().level()).isEqualTo(RecordLevel.STEADY);

        Applied second = RecordProjector.apply(first.state(), fund(LOAN, 2, D));
        assertThat(second.from()).isEqualTo(RecordLevel.STEADY);
        assertThat(second.to()).isEqualTo(RecordLevel.BUILDING);
        assertThat(second.cause()).isEqualTo(RecordProjector.Cause.CORRECTION);
        assertThat(second.state().level()).isEqualTo(RecordLevel.BUILDING);
        assertThat(second.state().lifetime().loansRepaidOnPlan()).isZero();
        assertThat(second.state().current().loansRepaidOnPlan()).isZero();
        assertThat(second.state().loansCompletedWithFundHelp()).isEqualTo(1);
    }

    @Test
    void aCorrectionNeverLowersTheLevelBelowWhatTheMembersOwnRecordSupports() {
        // The member's own first loan earns STEADY; a second loan turns out to be the fund's and takes nothing away.
        UUID third = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
        RecordState s = fold(deposit(2026, 1, 5), deposit(2026, 2, 5), deposit(2026, 3, 5), deposit(2026, 4, 5),
                deposit(2026, 5, 5), deposit(2026, 6, 5), paid(LOAN, 1, 0, D), RecordFact.loanRepaid(MEMBER, LOAN, 0, D));
        assertThat(s.level()).isEqualTo(RecordLevel.STEADY);

        RecordState after = fold(concat(new RecordFact[] {deposit(2026, 1, 5), deposit(2026, 2, 5), deposit(2026, 3, 5),
                deposit(2026, 4, 5), deposit(2026, 5, 5), deposit(2026, 6, 5), paid(LOAN, 1, 0, D),
                RecordFact.loanRepaid(MEMBER, LOAN, 0, D), paid(third, 1, 0, D), RecordFact.loanRepaid(MEMBER, third, 0, D)},
                fund(third, 1, D)));

        assertThat(after.level()).as("the member's own first loan still supports STEADY").isEqualTo(RecordLevel.STEADY);
        assertThat(after.lifetime().loansRepaidOnPlan()).isEqualTo(1);
        assertThat(after.loansCompletedWithFundHelp()).isEqualTo(1);
    }

    @Test
    void aCorrectionAfterADefaultDoesNotRaiseTheLevelAgain() {
        RecordState s = fold(deposit(2026, 1, 5), deposit(2026, 2, 5), deposit(2026, 3, 5), deposit(2026, 4, 5),
                deposit(2026, 5, 5), deposit(2026, 6, 5), paid(LOAN, 1, 0, D), RecordFact.loanRepaid(MEMBER, LOAN, 0, D),
                RecordFact.loanDefaulted(MEMBER, OTHER_LOAN, D.plusDays(1)), fund(LOAN, 1, D.plusDays(2)));

        assertThat(s.level()).isEqualTo(RecordLevel.NEW);
        assertThat(s.defaults()).isEqualTo(1);
    }

    // ---- a voided loan leaves the record (ADR 0025 follow-up) ----------------------------------------------------------

    private RecordFact[] sixMonthsSaving() {
        return new RecordFact[] {deposit(2026, 1, 5), deposit(2026, 2, 5), deposit(2026, 3, 5), deposit(2026, 4, 5),
                deposit(2026, 5, 5), deposit(2026, 6, 5)};
    }

    private static RecordFact disbursed(UUID loan) {
        return RecordFact.loanDisbursed("disb-" + loan, MEMBER, loan, D.minusDays(30));
    }

    private static RecordFact voided(UUID loan) {
        return RecordFact.loanVoided(MEMBER, loan, D.plusDays(40));
    }

    private static RecordState replay(RecordLevel levelBefore, RecordFact... facts) {
        return RecordProjector.replay(MEMBER, levelBefore, java.util.Arrays.asList(facts)).state();
    }

    @Test
    void aVoidedLoanLeavesNoLoanNoInstalmentsNoOnPlanCountAndNoLevelCredit() {
        RecordFact[] saving = sixMonthsSaving();
        RecordFact[] loan = {disbursed(LOAN), paid(LOAN, 1, 0, D), paid(LOAN, 2, 10, D), RecordFact.loanRepaid(MEMBER, LOAN, 0, D)};
        RecordState before = fold(concat(saving, loan));
        assertThat(before.level()).isEqualTo(RecordLevel.STEADY);
        assertThat(before.loansDisbursed()).isEqualTo(1);

        Applied after = RecordProjector.replay(MEMBER, before.level(),
                java.util.stream.Stream.concat(java.util.stream.Stream.concat(java.util.Arrays.stream(saving), java.util.Arrays.stream(loan)),
                        java.util.stream.Stream.of(voided(LOAN))).toList());

        assertThat(after.cause()).isEqualTo(RecordProjector.Cause.CORRECTION);
        assertThat(after.from()).isEqualTo(RecordLevel.STEADY);
        assertThat(after.to()).isEqualTo(RecordLevel.BUILDING);
        RecordState s = after.state();
        assertThat(s.loansDisbursed()).isZero();
        assertThat(s.lifetime().loansRepaidOnPlan()).isZero();
        assertThat(s.lifetime().loansRepaidOffPlan()).isZero();
        assertThat(s.lifetime().instalmentsOnTime()).isZero();
        assertThat(s.lifetime().instalmentsLate()).isZero();
        assertThat(s.lifetime().openLateInstalments()).isZero();
        assertThat(s.current()).isEqualTo(s.lifetime());
        assertThat(before.lifetime().instalmentsLate()).as("the loan really counted before the void").isEqualTo(1);
        assertThat(s.lifetime().onTimeSavingMonths()).as("the member's savings are untouched").isEqualTo(6);
        assertThat(s.settled()).containsExactly("void:" + LOAN);
        assertThat(s.openLate()).isEmpty();
    }

    @Test
    void aVoidedLoanThatCausedNoRiseLeavesTheLevelAlone() {
        RecordFact[] saving = sixMonthsSaving();
        Applied after = RecordProjector.replay(MEMBER, RecordLevel.BUILDING, java.util.stream.Stream
                .concat(java.util.Arrays.stream(saving), java.util.stream.Stream.of(disbursed(LOAN), voided(LOAN))).toList());

        assertThat(after.levelChanged()).isFalse();
        assertThat(after.to()).isEqualTo(RecordLevel.BUILDING);
    }

    @Test
    void aDefaultOnAVoidedLoanIsErased() {
        RecordFact[] saving = sixMonthsSaving();
        RecordFact[] facts = concat(saving, disbursed(LOAN), RecordFact.loanDefaulted(MEMBER, LOAN, D), voided(LOAN));
        RecordState defaulted = fold(java.util.Arrays.copyOf(facts, facts.length - 1));
        assertThat(defaulted.level()).isEqualTo(RecordLevel.NEW);
        assertThat(defaulted.defaults()).isEqualTo(1);

        Applied after = RecordProjector.replay(MEMBER, defaulted.level(), java.util.Arrays.asList(facts));

        assertThat(after.state().defaults()).isZero();
        assertThat(after.state().level()).as("what the member's own saving supports").isEqualTo(RecordLevel.BUILDING);
        assertThat(after.cause()).isEqualTo(RecordProjector.Cause.CORRECTION);
        assertThat(after.state().current().onTimeSavingMonths()).isEqualTo(6);
    }

    @Test
    void anotherLoanOfTheMemberKeepsItsOwnCounts() {
        RecordFact[] saving = sixMonthsSaving();
        RecordState s = RecordProjector.replay(MEMBER, RecordLevel.STEADY, java.util.Arrays.asList(concat(saving, disbursed(LOAN),
                disbursed(OTHER_LOAN), paid(LOAN, 1, 0, D), paid(OTHER_LOAN, 1, 0, D), RecordFact.loanRepaid(MEMBER, OTHER_LOAN, 0, D),
                voided(LOAN)))).state();

        assertThat(s.loansDisbursed()).isEqualTo(1);
        assertThat(s.lifetime().loansRepaidOnPlan()).isEqualTo(1);
        assertThat(s.lifetime().instalmentsOnTime()).isEqualTo(1);
        assertThat(s.level()).isEqualTo(RecordLevel.STEADY);
    }

    @Test
    void factsOfAVoidedLoanThatArriveAfterTheVoidAreStoredButNeverCounted() {
        RecordState voidedFirst = fold(concat(sixMonthsSaving(), voided(LOAN), disbursed(LOAN), paid(LOAN, 1, 0, D), missed(LOAN, 2, D),
                fund(LOAN, 3, D), RecordFact.loanRepaid(MEMBER, LOAN, 1, D), RecordFact.loanDefaulted(MEMBER, LOAN, D)));
        RecordState clean = replay(RecordLevel.NEW, concat(sixMonthsSaving(), voided(LOAN)));

        assertThat(voidedFirst.loansDisbursed()).isZero();
        assertThat(voidedFirst.lifetime()).isEqualTo(clean.lifetime());
        assertThat(voidedFirst.current()).isEqualTo(clean.current());
        assertThat(voidedFirst.openLate()).isEmpty();
        assertThat(voidedFirst.defaults()).isZero();
        assertThat(voidedFirst.level()).isEqualTo(RecordLevel.BUILDING);
        assertThat(voidedFirst.settled()).containsExactly("void:" + LOAN);
        assertThat(voidedFirst.factCount()).isEqualTo(clean.factCount() + 6);
    }

    @Test
    void theVoidIsTheSameWhateverOrderTheLoanFactsAndTheVoidArrive() {
        RecordFact[] loanFacts = {disbursed(LOAN), paid(LOAN, 1, 0, D), paid(LOAN, 2, 0, D), RecordFact.loanRepaid(MEMBER, LOAN, 0, D)};
        RecordState clean = replay(RecordLevel.NEW, concat(sixMonthsSaving(), voided(LOAN)));

        // The void first, then each loan fact folded one at a time on top (the guard ignores them).
        RecordState early = fold(concat(sixMonthsSaving(), voided(LOAN)));
        RecordState lateFacts = fold2(early, loanFacts);
        // The loan first, then the void as a rebuild.
        RecordState rebuilt = replay(RecordLevel.STEADY, concat(concat(sixMonthsSaving(), loanFacts), voided(LOAN)));

        for (RecordState s : List.of(lateFacts, rebuilt)) {
            assertThat(s.loansDisbursed()).isEqualTo(clean.loansDisbursed());
            assertThat(s.lifetime()).isEqualTo(clean.lifetime());
            assertThat(s.current()).isEqualTo(clean.current());
            assertThat(s.level()).isEqualTo(clean.level());
            assertThat(s.settled()).isEqualTo(clean.settled());
        }
    }

    @Test
    void replayingTwiceGivesTheSameState() {
        RecordFact[] facts = concat(sixMonthsSaving(), disbursed(LOAN), paid(LOAN, 1, 0, D), voided(LOAN));

        assertThat(replay(RecordLevel.BUILDING, facts)).isEqualTo(replay(RecordLevel.BUILDING, facts));
    }

    private static RecordFact[] concat(RecordFact[] head, RecordFact... tail) {
        RecordFact[] all = java.util.Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }
}
