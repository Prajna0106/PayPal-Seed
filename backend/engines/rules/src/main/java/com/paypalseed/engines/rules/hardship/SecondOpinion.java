package com.paypalseed.engines.rules.hardship;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The AI second opinion is escalate-only (03 §4). The deterministic level is the floor: the model may ask
 * for one step more, only on a borderline score, and only by citing signals the code itself produced.
 */
public final class SecondOpinion {

    /** The model's answer, already JSON-schema validated by the caller. */
    public record Opinion(RiskLevel suggestedLevel, List<String> citedSignals, String rationaleCode) {

        public Opinion {
            Objects.requireNonNull(suggestedLevel, "suggestedLevel");
            citedSignals = List.copyOf(citedSignals);
        }
    }

    public sealed interface Outcome permits Accepted, Ignored, Rejected {
    }

    /** The escalation is recorded as its own {@link Signal#AI_ESCALATION} signal. */
    public record Accepted(RiskLevel newLevel, Signal signal) implements Outcome {
    }

    /** Not an error: the opinion simply cannot change anything (for example it suggested a lower level). */
    public record Ignored(String reason) implements Outcome {
    }

    /** The opinion broke a rule (it cited a signal that did not fire); counts towards the kill switch. */
    public record Rejected(String reason) implements Outcome {
    }

    private SecondOpinion() {
    }

    /** One point under WATCH, LIKELY_MISS or IMMINENT. */
    public static boolean borderline(int points, HardshipPolicy policy) {
        HardshipPolicy.Scoring s = policy.scoring();
        return points == s.watchAt() - 1 || points == s.likelyMissAt() - 1 || points == s.imminentAt() - 1;
    }

    public static Outcome evaluate(ScoreResult result, Opinion opinion, boolean outreachAllowed, HardshipPolicy policy) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(opinion, "opinion");
        if (!borderline(result.points(), policy)) {
            return new Ignored("not borderline");
        }
        if (!opinion.suggestedLevel().atLeast(result.level()) || opinion.suggestedLevel() == result.level()) {
            return new Ignored("lower or equal: escalate-only");
        }
        if (opinion.suggestedLevel() != result.level().next()) {
            return new Ignored("more than one step");
        }
        Set<String> cited = new LinkedHashSet<>(opinion.citedSignals());
        if (cited.isEmpty() || !cited.stream().allMatch(result::fired)) {
            return new Rejected("cites a signal that did not fire");
        }
        int support = result.signals().stream().filter(s -> cited.contains(s.code())).mapToInt(Signal::points).sum();
        if (cited.size() < 2 && support < policy.scoring().watchAt()) {
            return new Rejected("one weak signal is not enough");
        }
        if (!outreachAllowed) {
            return new Ignored("no outreach allowed");
        }
        RiskLevel newLevel = opinion.suggestedLevel();
        int added = newLevel.threshold(policy.scoring()) - result.points();
        return new Accepted(newLevel, Signal.of(Signal.AI_ESCALATION, added, "pointsBefore", result.points(),
                "threshold", newLevel.threshold(policy.scoring()), "citedSignals", cited.size()));
    }
}
