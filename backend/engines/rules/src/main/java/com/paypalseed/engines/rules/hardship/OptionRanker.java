package com.paypalseed.engines.rules.hardship;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chooses which option to label "suggested for your situation" (03 §6). Every option is still shown, none
 * is preselected, and the member's choice is final. The model never picks: this code does.
 *
 * <p>A persistent drop means a pause only moves the problem, so the plan must get smaller. A short dip is
 * best met by the least change, which is also the quickest to approve.
 */
public final class OptionRanker {

    private static final Comparator<OptionCandidate> BEST = Comparator
            .comparing((OptionCandidate c) -> c.metrics().meanDelayDays())
            .thenComparingInt(c -> c.metrics().instalmentsMoved())
            .thenComparing(c -> c.tier() == ApprovalTier.AUTO_POLICY ? 0 : 1)
            .thenComparing(c -> c.kind().label())
            .thenComparingInt(c -> c.arg(OptionCandidate.EXTRA_MONTHS));

    private static final List<OptionKind> LEAST_INTRUSIVE_FIRST =
            List.of(OptionKind.D_PAYMENT, OptionKind.A_MOVE_DATE, OptionKind.B_PAUSE, OptionKind.C_RESTRUCTURE);

    private OptionRanker() {
    }

    /** @param persistence empty when there is no income data: then nothing is suggested */
    public static Optional<OptionKind> recommend(List<OptionCandidate> candidates,
                                                 Optional<IncomeFeatures.Persistence> persistence) {
        return recommendCandidate(candidates, persistence).map(OptionCandidate::kind);
    }

    /** As {@link #recommend}, returning the exact variant (which {@code extraMonths} for C). */
    public static Optional<OptionCandidate> recommendCandidate(List<OptionCandidate> candidates,
                                                               Optional<IncomeFeatures.Persistence> persistence) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(persistence, "persistence");
        if (persistence.isEmpty()) {
            return Optional.empty();
        }
        List<OptionCandidate> ok = candidates.stream().filter(OptionCandidate::policyOk).filter(OptionRanker::recommendable).toList();
        List<OptionCandidate> pool;
        if (persistence.get() == IncomeFeatures.Persistence.PERSISTENT) {
            List<OptionCandidate> restructures = ok.stream().filter(c -> c.kind() == OptionKind.C_RESTRUCTURE).toList();
            pool = restructures.stream().filter(c -> Boolean.TRUE.equals(c.metrics().sustainable())).toList();
            if (pool.isEmpty()) {
                pool = restructures.stream().max(Comparator.comparingInt(c -> c.arg(OptionCandidate.EXTRA_MONTHS)))
                        .map(List::of).orElse(List.of());
            }
        } else {
            pool = LEAST_INTRUSIVE_FIRST.stream()
                    .map(kind -> ok.stream().filter(c -> c.kind() == kind).toList())
                    .filter(l -> !l.isEmpty())
                    .findFirst()
                    .orElse(List.of());
        }
        return pool.stream().min(BEST);
    }

    /**
     * The restructure variant to offer: the smallest {@code extraMonths} that is sustainable, otherwise the
     * largest one (the caller flags it "still tight").
     */
    public static Optional<OptionCandidate> restructureVariant(List<OptionCandidate> restructures) {
        List<OptionCandidate> c = restructures.stream().filter(o -> o.kind() == OptionKind.C_RESTRUCTURE).toList();
        Comparator<OptionCandidate> byMonths = Comparator.comparingInt(o -> o.arg(OptionCandidate.EXTRA_MONTHS));
        return c.stream().filter(o -> Boolean.TRUE.equals(o.metrics().sustainable())).min(byMonths)
                .or(() -> c.stream().max(byMonths));
    }

    /** Option D is only ever suggested when it pays the whole target instalment. */
    private static boolean recommendable(OptionCandidate c) {
        return c.kind() != OptionKind.D_PAYMENT || c.arg(OptionCandidate.COVERS_INSTALMENT) == 1;
    }
}
