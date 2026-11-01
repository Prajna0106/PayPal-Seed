package com.paypalseed.engines.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.engines.rules.EligibilityEngine.EligibilityEvaluation;
import com.paypalseed.engines.rules.EligibilityEngine.EligibilityInput;
import com.paypalseed.engines.rules.EligibilityEngine.SizingDecision;
import com.paypalseed.engines.rules.record.RecordLevel;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** A higher credit record level raises the loan ceiling; a NEW record changes nothing. */
class EligibilityRecordLevelTest {

    private static EligibilityInput saver(int completedLoans, RecordLevel level) {
        return new EligibilityInput(6, 6, 0, 15_000L, OptionalLong.of(300_000L), completedLoans, false, level);
    }

    @Test
    void aNewRecordBehavesExactlyAsTheLadderAloneDid() {
        for (int completed = 0; completed <= 3; completed++) {
            EligibilityInput without = new EligibilityInput(6, 6, 0, 15_000L, OptionalLong.of(300_000L), completed, false);
            EligibilityEvaluation before = EligibilityEngine.evaluate(without);
            EligibilityEvaluation withNew = EligibilityEngine.evaluate(saver(completed, RecordLevel.NEW));

            assertThat(withNew.tier()).isEqualTo(before.tier());
            assertThat(withNew.tierLimitCents()).isEqualTo(before.tierLimitCents());
            assertThat(withNew.maxLoanCents()).isEqualTo(before.maxLoanCents());
            assertThat(withNew.minTermMonths()).isEqualTo(before.minTermMonths());
            assertThat(withNew.maxTermMonths()).isEqualTo(before.maxTermMonths());
            assertThat(withNew.recordLevel()).isEqualTo(RecordLevel.NEW);
        }
        assertThat(EligibilityEngine.evaluate(saver(0, RecordLevel.NEW)).tierLimitCents()).isEqualTo(150_000L);
    }

    @Test
    void theInputWithoutALevelIsTheSameAsANewRecord() {
        assertThat(new EligibilityInput(6, 6, 0, 15_000L, OptionalLong.empty(), 0, false).recordLevel()).isEqualTo(RecordLevel.NEW);
        assertThat(new EligibilityInput(6, 6, 0, 15_000L, OptionalLong.empty(), 0, false, null).recordLevel()).isEqualTo(RecordLevel.NEW);
    }

    @Test
    void eachHigherLevelRaisesTheCeilingForAFirstTimeBorrowerAndNeverLowersIt() {
        long previous = 0;
        for (RecordLevel level : RecordLevel.values()) {
            long limit = EligibilityEngine.evaluate(saver(0, level)).tierLimitCents();
            assertThat(limit).as(level.name()).isGreaterThanOrEqualTo(previous).isGreaterThanOrEqualTo(150_000L);
            previous = limit;
        }
        assertThat(EligibilityEngine.evaluate(saver(0, RecordLevel.STEADY)).tierLimitCents()).isEqualTo(300_000L);
        assertThat(EligibilityEngine.evaluate(saver(0, RecordLevel.TRUSTED)).tierLimitCents()).isEqualTo(500_000L);
        assertThat(EligibilityEngine.evaluate(saver(0, RecordLevel.EXEMPLARY)).tierLimitCents()).isEqualTo(750_000L);
    }

    @ParameterizedTest
    @EnumSource(RecordLevel.class)
    void theRecordNeverLowersWhatRepaidLoansAlreadyEarned(RecordLevel level) {
        EligibilityEvaluation twoRepaid = EligibilityEngine.evaluate(saver(2, level));

        assertThat(twoRepaid.tierLimitCents()).isGreaterThanOrEqualTo(LoanTier.LEVEL_3.maxLoanCents());
        assertThat(twoRepaid.tier().level()).isGreaterThanOrEqualTo(LoanTier.LEVEL_3.level());
    }

    @Test
    void aLevelAboveTheLadderBringsItsRungAndTheTermsThatGoWithIt() {
        EligibilityEvaluation steadyFirstLoan = EligibilityEngine.evaluate(saver(0, RecordLevel.STEADY));
        EligibilityEvaluation trustedFirstLoan = EligibilityEngine.evaluate(saver(0, RecordLevel.TRUSTED));

        assertThat(steadyFirstLoan.tier()).isEqualTo(LoanTier.LEVEL_2);
        assertThat(trustedFirstLoan.tier()).isEqualTo(LoanTier.LEVEL_3);
        assertThat(trustedFirstLoan.maxTermMonths()).isEqualTo(18);
        assertThat(EligibilityEngine.evaluate(saver(0, RecordLevel.NEW)).maxTermMonths()).isEqualTo(12);
    }

    @Test
    void anExemplaryRecordLiftsTheCeilingAboveTheTopRungAndSizingHonoursIt() {
        EligibilityInput rich = new EligibilityInput(12, 12, 0, 50_000L, OptionalLong.of(900_000L), 3, false, RecordLevel.EXEMPLARY);

        EligibilityEvaluation eval = EligibilityEngine.evaluate(rich);
        SizingDecision sevenThousand = EligibilityEngine.checkApplication(rich, 700_000L, 18);
        SizingDecision tooMuch = EligibilityEngine.checkApplication(rich, 800_000L, 18);

        assertThat(eval.tierLimitCents()).isEqualTo(750_000L);
        assertThat(eval.maxLoanCents()).isLessThanOrEqualTo(750_000L);
        assertThat(sevenThousand.allowed()).isTrue();
        assertThat(tooMuch.allowed()).isFalse();
        assertThat(tooMuch.reasons()).anyMatch(r -> r.contains("$7500"));
        // the same request from a member whose record is only at the top rung of the ladder is refused
        EligibilityInput ladderOnly = new EligibilityInput(12, 12, 0, 50_000L, OptionalLong.of(900_000L), 3, false);
        assertThat(EligibilityEngine.checkApplication(ladderOnly, 700_000L, 18).allowed()).isFalse();
    }

    @Test
    void theRecordDoesNotUnlockAnythingWhileTheSavingStreakIsBroken() {
        EligibilityInput broken = new EligibilityInput(2, 2, 0, 15_000L, OptionalLong.empty(), 0, false, RecordLevel.EXEMPLARY);

        EligibilityEvaluation eval = EligibilityEngine.evaluate(broken);

        assertThat(eval.eligible()).isFalse();
        assertThat(eval.tierLimitCents()).isZero();
        assertThat(eval.limitReason()).isEmpty();
    }

    @Test
    void theReasonSaysInPlainWordsWhichLimitAppliedAndWhyWithoutAScore() {
        assertThat(EligibilityEngine.evaluate(saver(0, RecordLevel.NEW)).limitReason())
            .isEqualTo("Your limit is $1500.00. It grows as you save and repay on time.");
        assertThat(EligibilityEngine.evaluate(saver(0, RecordLevel.STEADY)).limitReason())
            .isEqualTo("Your record is at Steady, which lifts your limit from $1500.00 to $3000.00.");
        assertThat(EligibilityEngine.evaluate(saver(1, RecordLevel.STEADY)).limitReason())
            .isEqualTo("Your record is at Steady and your limit is $3000.00. It grows as you keep saving and repaying on time.");
        assertThat(EligibilityEngine.evaluate(saver(0, RecordLevel.TRUSTED)).reasons())
            .contains(EligibilityEngine.evaluate(saver(0, RecordLevel.TRUSTED)).limitReason());
        for (RecordLevel level : RecordLevel.values()) {
            assertThat(EligibilityEngine.evaluate(saver(0, level)).limitReason()).doesNotContainIgnoringCase("score");
        }
    }

    @Test
    void anActiveLoanStillBlocksANewOneWhateverTheLevel() {
        EligibilityInput busy = new EligibilityInput(6, 6, 0, 15_000L, OptionalLong.empty(), 1, true, RecordLevel.EXEMPLARY);

        EligibilityEvaluation eval = EligibilityEngine.evaluate(busy);

        assertThat(eval.eligible()).isFalse();
        assertThat(eval.reasons()).anyMatch(r -> r.contains("Only one active microloan"));
    }
}
