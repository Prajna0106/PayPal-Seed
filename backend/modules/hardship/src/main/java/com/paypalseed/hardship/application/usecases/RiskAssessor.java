package com.paypalseed.hardship.application.usecases;

import com.paypalseed.engines.rules.hardship.FeatureExtractor;
import com.paypalseed.engines.rules.hardship.HardshipPolicy;
import com.paypalseed.engines.rules.hardship.Hysteresis;
import com.paypalseed.engines.rules.hardship.IncomeFeatures;
import com.paypalseed.engines.rules.hardship.RiskLevel;
import com.paypalseed.engines.rules.hardship.ScoreResult;
import com.paypalseed.engines.rules.hardship.Scorecard;
import com.paypalseed.engines.rules.hardship.ScorecardInput;
import com.paypalseed.engines.rules.hardship.Signal;
import com.paypalseed.hardship.infrastructure.persistence.ScorecardRepository;
import com.paypalseed.hardship.infrastructure.persistence.ScorecardRepository.Run;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.RiskFacts;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scores one loan on one date (03 §2, §3, §3.1). The inputs are the member's own consented income and their
 * own loan behaviour; the result is stored once per loan and date, so the daily scan and the event wake-ups
 * can both ask without double-counting.
 */
@Service
public class RiskAssessor {

    /** Everything decided about a loan today. {@code level} is after hysteresis; {@code score.level()} before it. */
    public record Assessment(RiskFacts facts, Optional<IncomeFeatures> income, ScoreResult score, RiskLevel level, Run run) {

        public int targetSeq() {
            return facts.targetSeq();
        }
    }

    private final LendingFacade lending;
    private final UnderwritingFacade underwriting;
    private final ScorecardRepository scorecards;
    private final HardshipSettings settings;

    public RiskAssessor(LendingFacade lending, UnderwritingFacade underwriting, ScorecardRepository scorecards,
                        HardshipSettings settings) {
        this.lending = lending;
        this.underwriting = underwriting;
        this.scorecards = scorecards;
        this.settings = settings;
    }

    /** Empty when the loan does not exist or owes nothing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Assessment> assess(UUID loanId, LocalDate asOf, boolean memberRequested) {
        Optional<RiskFacts> found = lending.riskFacts(loanId, asOf);
        if (found.isEmpty() || found.get().targetSeq() == null || found.get().targetRemainingCents() == null
                || found.get().targetRemainingCents() <= 0) {
            return Optional.empty();
        }
        RiskFacts facts = found.get();
        HardshipPolicy policy = settings.policy();
        Optional<IncomeSummary> summary = underwriting.incomeSummary(facts.memberId(), asOf);
        long instalment = facts.targetRemainingCents();
        Optional<IncomeFeatures> income = summary.flatMap(s -> FeatureExtractor.extract(s.weeks(), instalment, policy));
        boolean paydaySlip = summary.isPresent() && income.isPresent() && paydaySlipped(summary.get(), income.get(), asOf);
        ScoreResult score = Scorecard.score(new ScorecardInput(income, instalment, facts.failedChargesLast14Days(),
                facts.failedTwiceOnTarget(), paydaySlip, facts.dpd(), facts.lateWithin90Days(), false, memberRequested), policy);

        Optional<Run> stored = scorecards.find(loanId, asOf);
        Run run;
        RiskLevel level;
        if (stored.isPresent()) {
            run = stored.get();
            level = memberRequested ? RiskLevel.IMMINENT : RiskLevel.valueOf(run.level());
        } else {
            Optional<Run> previous = scorecards.previousInCycle(loanId, facts.targetSeq(), asOf);
            Hysteresis.State state = Hysteresis.apply(score.level(),
                    previous.map(p -> RiskLevel.valueOf(p.level())).orElse(RiskLevel.NONE),
                    previous.map(Run::belowCount).orElse(0), policy);
            level = state.level();
            run = new Run(Ids.newId(), loanId, facts.memberId(), asOf, facts.scheduleVersion(), facts.targetSeq(),
                    score.level().name(), level.name(), state.belowCount(), score.points(), features(facts, income, score),
                    policy.version(), policy.scorecardVersion());
            if (!scorecards.insert(run)) {
                run = scorecards.find(loanId, asOf).orElseThrow();
            }
        }
        List<Signal> fired = score.signals();
        scorecards.insertSignals(run.id(), fired.stream().filter(s -> !Signal.MEMBER_REQUEST.equals(s.code())).toList(),
                "SCORECARD", null);
        if (memberRequested) {
            scorecards.insertSignals(run.id(), fired.stream().filter(s -> Signal.MEMBER_REQUEST.equals(s.code())).toList(),
                    "MEMBER", null);
        }
        return Optional.of(new Assessment(facts, income, score, level, run));
    }

    /**
     * S4: the usual payday passed at least 3 days ago and the last week brought in under half a usual week. The
     * weekly bucket ending yesterday covers that payday whenever it was within the last 7 days.
     */
    static boolean paydaySlipped(IncomeSummary summary, IncomeFeatures income, LocalDate asOf) {
        if (summary.usualPaydayDow() == null) {
            return false;
        }
        LocalDate lastPayday = asOf.minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.of(summary.usualPaydayDow())));
        long daysSince = asOf.toEpochDay() - lastPayday.toEpochDay();
        long[] weeks = summary.weeks();
        long lastWeek = weeks[weeks.length - 1];
        return daysSince >= 3 && daysSince <= 7 && lastWeek * 2 < income.baselineWeeklyCents();
    }

    private static Map<String, Long> features(RiskFacts facts, Optional<IncomeFeatures> income, ScoreResult score) {
        Map<String, Long> f = new LinkedHashMap<>();
        f.put("points", (long) score.points());
        f.put("dpd", (long) facts.dpd());
        f.put("targetRemainingCents", facts.targetRemainingCents());
        f.put("failedChargesLast14Days", (long) facts.failedChargesLast14Days());
        f.put("pendingCents", facts.pendingCents());
        income.ifPresent(i -> {
            f.put("baselineWeeklyCents", i.baselineWeeklyCents());
            f.put("last4Cents", i.last4Cents());
            f.put("dropBps", (long) i.dropBps());
            f.put("recentMonthlyCents", i.recentMonthlyCents());
            f.put("coverageBps", i.coverageBps());
            f.put("weeksBelow", (long) i.weeksBelow());
            f.put("persistent", i.persistence() == IncomeFeatures.Persistence.PERSISTENT ? 1L : 0L);
        });
        return f;
    }
}
