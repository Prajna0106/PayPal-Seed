package com.paypalseed.hardship.application.usecases;

import com.paypalseed.engines.rules.hardship.GroundingChecker;
import com.paypalseed.engines.rules.hardship.GroundingChecker.Allowed;
import com.paypalseed.engines.rules.hardship.GroundingChecker.Audience;
import com.paypalseed.engines.rules.hardship.HardshipPolicy;
import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.OfferModel.OptionRow;
import com.paypalseed.hardship.infrastructure.persistence.ScorecardRepository.StoredSignal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds, in code, every figure an AI text about a case may quote (03 §11). Member texts may name the target
 * instalment, the options' own numbers and the income drop in whole percent; officer briefs may also cite every
 * signal's evidence and the scorecard points. Keys say the unit: {@code *Cents} money, {@code *Bps} percent,
 * {@code *Date} epoch days, {@code *Days} a duration, anything else a count.
 */
final class AllowedFigures {

    private AllowedFigures() {
    }

    static Allowed build(CaseRow c, List<OptionRow> options, List<StoredSignal> signals, Audience audience, HardshipPolicy policy) {
        Set<Long> money = new HashSet<>();
        Set<Integer> percent = new HashSet<>();
        Set<LocalDate> dates = new HashSet<>();
        Set<Integer> counts = new HashSet<>();
        Set<Integer> days = new HashSet<>();
        Set<Integer> months = new HashSet<>();
        Set<String> labels = new HashSet<>();

        money.add(c.targetAmountCents());
        dates.add(c.targetDueDate());
        counts.add(policy.scoring().recentWeeks());
        for (OptionRow o : options) {
            labels.add(o.label());
            o.summary().forEach((k, v) -> add(k, v, money, percent, dates, counts, days));
            if (o.args().containsKey("days")) {
                days.add(o.args().get("days"));
            }
            if ("B".equals(o.label())) {
                months.add(1);
            }
            if (o.args().containsKey("extraMonths")) {
                months.add(o.args().get("extraMonths"));
                counts.add(o.args().get("extraMonths"));
            }
        }
        for (StoredSignal s : signals) {
            Long drop = s.evidence().get("dropBps");
            if (drop != null) {
                percent.add(drop.intValue());
                percent.add((int) Math.round(drop / 100.0) * 100);
            }
            if (audience == Audience.OFFICER) {
                s.evidence().forEach((k, v) -> add(k, v, money, percent, dates, counts, days));
                counts.add(s.points());
            }
        }
        if (audience == Audience.OFFICER) {
            counts.add(c.points());
            counts.add(c.targetSeq());
            counts.add(policy.scoring().watchAt());
            counts.add(policy.scoring().likelyMissAt());
            counts.add(policy.scoring().imminentAt());
        }
        return new GroundingChecker.Allowed(money, percent, dates, counts, days, months, labels);
    }

    private static void add(String key, long value, Set<Long> money, Set<Integer> percent, Set<LocalDate> dates,
                            Set<Integer> counts, Set<Integer> days) {
        if (key.endsWith("Cents")) {
            money.add(value);
        } else if (key.endsWith("Bps")) {
            percent.add((int) value);
            percent.add((int) Math.round(value / 100.0) * 100);
        } else if (key.endsWith("Date")) {
            dates.add(LocalDate.ofEpochDay(value));
        } else if (key.endsWith("Days")) {
            days.add((int) value);
            counts.add((int) value);
        } else {
            counts.add((int) value);
        }
    }

    /** Does a proposal's figure appear in the officer allowance? */
    static boolean contains(Allowed a, String unit, Object value) {
        try {
            return switch (unit) {
                case "USD_CENTS" -> a.moneyCents().contains(((Number) value).longValue());
                case "PERCENT" -> {
                    int bps = ((Number) value).intValue() * 100;
                    yield a.percentBps().stream().anyMatch(p -> Math.abs(p - bps) <= 50);
                }
                case "DAYS" -> a.durationsDays().contains(((Number) value).intValue()) || a.counts().contains(((Number) value).intValue());
                case "DATE" -> a.dates().contains(LocalDate.parse(value.toString()));
                case "COUNT" -> a.counts().contains(((Number) value).intValue()) || a.durationsMonths().contains(((Number) value).intValue());
                default -> false;
            };
        } catch (RuntimeException e) {
            return false;
        }
    }

    static Map<String, String> optionNames() {
        return Map.of("MOVE_DATE", "A", "PAUSE_ONE_MONTH", "B", "EXTEND_PLAN", "C", "PAY_WHAT_YOU_CAN", "D");
    }
}
