package com.paypalseed.hardship.application.usecases;

import com.paypalseed.engines.rules.hardship.ApprovalTier;
import com.paypalseed.engines.rules.hardship.HardshipCaps;
import com.paypalseed.engines.rules.hardship.HardshipPolicy;
import com.paypalseed.engines.rules.hardship.IncomeFeatures;
import com.paypalseed.engines.rules.hardship.OptionCandidate;
import com.paypalseed.engines.rules.hardship.OptionKind;
import com.paypalseed.engines.rules.hardship.OptionMetrics;
import com.paypalseed.engines.rules.hardship.OptionRanker;
import com.paypalseed.engines.rules.hardship.OwedRow;
import com.paypalseed.engines.rules.hardship.PaymentOption;
import com.paypalseed.engines.rules.hardship.SnapshotHash;
import com.paypalseed.engines.rules.hardship.SnapshotRow;
import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.OfferModel.OfferRow;
import com.paypalseed.hardship.domain.model.OfferModel.OptionRow;
import com.paypalseed.hardship.domain.model.OfferModel.ProposedRow;
import com.paypalseed.hardship.infrastructure.persistence.CaseRepository;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.InstalmentLine;
import com.paypalseed.lending.api.dto.LendingDtos.ReschedulePreview;
import com.paypalseed.lending.api.dto.LendingDtos.ReschedulePrimitive;
import com.paypalseed.lending.api.dto.LendingDtos.RescheduleRequest;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.lending.api.dto.LendingDtos.Slot;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds an offer: options A to D, each previewed by lending's real reschedule primitives (no writes), measured
 * and ranked by the engine, and frozen with a snapshot hash (03 §5 to §8). The model never computes a number.
 */
@Service
public class OfferBuilder {

    public static final String WARN_NOT_SUSTAINABLE = "NOT_SUSTAINABLE_AT_CURRENT_INCOME";
    public static final String WARN_STILL_TIGHT = "STILL_TIGHT";
    public static final String WARN_SHORTFALL = "LEAVES_SHORTFALL";

    /** An option's fresh preview: what applying it now would produce. */
    public record Fresh(String snapshotHash, List<ProposedRow> proposed) {

        public List<Slot> slots() {
            return proposed.stream().map(r -> new Slot(r.seq(), r.dueDate(), r.amountCents())).toList();
        }
    }

    public record Built(OfferRow offer, List<OptionRow> options) {
    }

    private record Candidate(OptionCandidate engine, List<ProposedRow> proposed, Map<String, Long> summary) {
    }

    private final LendingFacade lending;
    private final CaseRepository cases;
    private final HardshipSettings settings;
    private final TransactionTemplate isolated;

    public OfferBuilder(LendingFacade lending, CaseRepository cases, HardshipSettings settings,
                        PlatformTransactionManager txManager) {
        this.lending = lending;
        this.cases = cases;
        this.settings = settings;
        this.isolated = new TransactionTemplate(txManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.isolated.setReadOnly(true);
    }

    public Built build(CaseRow c, Optional<IncomeFeatures> income, int revision, LocalDate asOf) {
        HardshipPolicy policy = settings.policy();
        ScheduleView current = lending.currentSchedule(c.loanId());
        List<OwedRow> before = owed(current.instalments());
        Long recentMonthly = income.map(IncomeFeatures::recentMonthlyCents).orElse(null);
        HardshipCaps.History history = history(c.loanId(), asOf, policy);
        long targetRemaining = current.instalments().stream().filter(i -> i.seq() == c.targetSeq())
                .mapToLong(InstalmentLine::remainingCents).findFirst().orElse(c.targetAmountCents());

        List<Candidate> candidates = new ArrayList<>();
        for (int days = policy.options().maxMoveDays(); days >= 1; days--) {
            Optional<Candidate> a = reschedule(OptionKind.A_MOVE_DATE, c, before, Map.of("seq", c.targetSeq(), "days", days),
                    recentMonthly, history, asOf, policy);
            if (a.isPresent()) {
                candidates.add(a.get());
                break;
            }
        }
        reschedule(OptionKind.B_PAUSE, c, before, Map.of("seq", c.targetSeq()), recentMonthly, history, asOf, policy)
                .ifPresent(candidates::add);
        List<Candidate> restructures = new ArrayList<>();
        for (int k = 1; k <= policy.options().maxExtraMonths(); k++) {
            reschedule(OptionKind.C_RESTRUCTURE, c, before, Map.of(OptionCandidate.EXTRA_MONTHS, k), recentMonthly, history,
                    asOf, policy).ifPresent(restructures::add);
        }
        OptionRanker.restructureVariant(restructures.stream().map(Candidate::engine).toList())
                .flatMap(v -> restructures.stream().filter(r -> r.engine() == v).findFirst())
                .ifPresent(candidates::add);
        payment(current, before, targetRemaining, c, recentMonthly, policy).ifPresent(candidates::add);

        Optional<OptionCandidate> recommended = OptionRanker.recommendCandidate(
                candidates.stream().map(Candidate::engine).toList(), income.map(IncomeFeatures::persistence));
        UUID offerId = Ids.newId();
        List<OptionRow> options = new ArrayList<>();
        for (Candidate cand : candidates) {
            OptionCandidate e = cand.engine();
            if (!e.policyOk()) {
                continue;
            }
            boolean isRecommended = recommended.isPresent() && recommended.get() == e;
            options.add(new OptionRow(Ids.newId(), offerId, e.kind().label(), e.kind().primitive(), e.args(), cand.proposed(),
                    cand.summary(), snapshot(c.loanId(), current.version(), e.kind(), e.args(), cand.proposed()),
                    e.metrics().meanDelayDays(), e.metrics().maxInstalmentCents(), e.metrics().burdenBps(),
                    e.metrics().sustainable(), isRecommended, ApprovalTier.forKind(e.kind()).name(), warnings(e)));
        }
        LocalDate expires = c.targetDueDate().plusDays(policy.timing().offerExpiryDaysAfterDue());
        OfferRow offer = new OfferRow(offerId, c.id(), revision, current.version(), asOf, expires.isBefore(asOf) ? asOf : expires,
                recommended.map(r -> r.kind().label()).orElse(null), "ACTIVE");
        return new Built(offer, options);
    }

    /** Recomputes what an offered option would do right now; the hash differs when anything owed has changed. */
    public Fresh fresh(UUID loanId, OptionRow option) {
        ScheduleView current = lending.currentSchedule(loanId);
        OptionKind kind = OptionKind.fromLabel(option.label());
        List<ProposedRow> proposed = kind == OptionKind.D_PAYMENT
                ? rows(current.instalments())
                : rows(preview(request(loanId, kind, option.args())).proposed().instalments());
        return new Fresh(snapshot(loanId, current.version(), kind, option.args(), proposed), proposed);
    }

    /**
     * A preview runs in its own read-only transaction: lending refuses an impossible option by throwing, and a refusal
     * must not mark the caller's transaction rollback-only (the scan simply skips that option).
     */
    private ReschedulePreview preview(RescheduleRequest request) {
        return isolated.execute(status -> lending.previewReschedule(request));
    }

    public static RescheduleRequest request(UUID loanId, OptionKind kind, Map<String, Integer> args) {
        return switch (kind) {
            case A_MOVE_DATE -> new RescheduleRequest(loanId, ReschedulePrimitive.MOVE_DATE, args.get("seq"), args.get("days"), null);
            case B_PAUSE -> new RescheduleRequest(loanId, ReschedulePrimitive.DEFER_TO_END, args.get("seq"), null, null);
            case C_RESTRUCTURE -> new RescheduleRequest(loanId, ReschedulePrimitive.REAMORTIZE, null, null,
                    args.get(OptionCandidate.EXTRA_MONTHS));
            case D_PAYMENT -> throw new IllegalArgumentException("option D is a payment, not a reschedule");
        };
    }

    private Optional<Candidate> reschedule(OptionKind kind, CaseRow c, List<OwedRow> before, Map<String, Integer> args,
                                           Long recentMonthly, HardshipCaps.History history, LocalDate asOf,
                                           HardshipPolicy policy) {
        ReschedulePreview preview;
        try {
            preview = preview(request(c.loanId(), kind, args));
        } catch (DomainException e) {
            return Optional.empty();
        }
        List<ProposedRow> proposed = rows(preview.proposed().instalments());
        List<OwedRow> after = owed(preview.proposed().instalments());
        if (after.isEmpty()) {
            return Optional.empty();
        }
        OptionMetrics metrics;
        try {
            metrics = OptionMetrics.compute(before, after, asOf, recentMonthly, policy);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        boolean policyOk = HardshipCaps.violation(kind, history, policy).isEmpty();
        return Optional.of(new Candidate(new OptionCandidate(kind, args, metrics, policyOk), proposed,
                summary(kind, c, before, preview.proposed().instalments(), args)));
    }

    private Optional<Candidate> payment(ScheduleView current, List<OwedRow> before, long targetRemaining, CaseRow c,
                                        Long recentMonthly, HardshipPolicy policy) {
        if (targetRemaining <= 0) {
            return Optional.empty();
        }
        OptionalLong amount = PaymentOption.amountCents(recentMonthly, targetRemaining, policy);
        if (amount.isEmpty()) {
            return Optional.empty();
        }
        long pay = amount.getAsLong();
        Integer burden = recentMonthly == null || recentMonthly <= 0 ? null : (int) (pay * 10_000 / recentMonthly);
        Boolean sustainable = burden == null ? null : burden <= policy.options().maxBurdenBps();
        long maxRemaining = before.stream().mapToLong(OwedRow::remainingCents).max().orElse(targetRemaining);
        OptionMetrics metrics = new OptionMetrics(BigDecimal.ZERO.setScale(2), maxRemaining, burden, sustainable, 0);
        Map<String, Integer> args = Map.of("amountCents", (int) pay, OptionCandidate.COVERS_INSTALMENT, pay >= targetRemaining ? 1 : 0);
        Map<String, Long> summary = new LinkedHashMap<>();
        summary.put("payNowCents", pay);
        summary.put("stillDueOnDate", c.targetDueDate().toEpochDay());
        summary.put("stillDueCents", targetRemaining - pay);
        return Optional.of(new Candidate(new OptionCandidate(OptionKind.D_PAYMENT, args, metrics, true),
                rows(current.instalments()), summary));
    }

    private static Map<String, Long> summary(OptionKind kind, CaseRow c, List<OwedRow> before, List<InstalmentLine> proposed,
                                             Map<String, Integer> args) {
        Map<String, Long> s = new LinkedHashMap<>();
        List<InstalmentLine> owed = proposed.stream().filter(i -> i.remainingCents() > 0).toList();
        LocalDate last = owed.get(owed.size() - 1).dueDate();
        long max = owed.stream().mapToLong(InstalmentLine::remainingCents).max().orElse(0);
        switch (kind) {
            case A_MOVE_DATE -> {
                s.put("movedFromDate", c.targetDueDate().toEpochDay());
                s.put("movedToDate", c.targetDueDate().plusDays(args.get("days")).toEpochDay());
                s.put("days", (long) args.get("days"));
                s.put("amountCents", amountOf(proposed, c.targetSeq()));
            }
            case B_PAUSE -> {
                s.put("removedFromDate", c.targetDueDate().toEpochDay());
                s.put("addedOnDate", last.toEpochDay());
                s.put("amountCents", owed.get(owed.size() - 1).remainingCents());
                s.put("months", 1L);
            }
            case C_RESTRUCTURE -> {
                s.put("extraMonths", (long) args.get(OptionCandidate.EXTRA_MONTHS));
                s.put("instalments", (long) owed.size());
                s.put("firstDueDate", owed.get(0).dueDate().toEpochDay());
                s.put("minInstalmentCents", owed.stream().mapToLong(InstalmentLine::remainingCents).min().orElse(0));
            }
            case D_PAYMENT -> throw new IllegalStateException("payments are summarised separately");
        }
        s.put("lastDueDate", last.toEpochDay());
        s.put("maxInstalmentCents", max);
        s.put("owedBeforeCount", (long) before.size());
        return s;
    }

    private static long amountOf(List<InstalmentLine> lines, int seq) {
        return lines.stream().filter(i -> i.seq() == seq).mapToLong(InstalmentLine::remainingCents).findFirst().orElse(0);
    }

    private static List<String> warnings(OptionCandidate e) {
        List<String> w = new ArrayList<>();
        if (e.kind() == OptionKind.D_PAYMENT) {
            if (e.arg(OptionCandidate.COVERS_INSTALMENT) == 0) {
                w.add(WARN_SHORTFALL);
            }
        } else if (Boolean.FALSE.equals(e.metrics().sustainable())) {
            w.add(e.kind() == OptionKind.C_RESTRUCTURE ? WARN_STILL_TIGHT : WARN_NOT_SUSTAINABLE);
        }
        return w;
    }

    private HardshipCaps.History history(UUID loanId, LocalDate asOf, HardshipPolicy policy) {
        CaseRepository.AppliedCounts counts = cases.appliedCounts(loanId, asOf.minusDays(policy.options().capWindowDays()));
        return new HardshipCaps.History(counts.aInWindow(), counts.bInWindow(), counts.bTotal(), counts.cTotal());
    }

    private static String snapshot(UUID loanId, int version, OptionKind kind, Map<String, Integer> args,
                                   List<ProposedRow> proposed) {
        return SnapshotHash.of(loanId, version, kind.primitive(), args, proposed.stream()
                .map(r -> new SnapshotRow(r.seq(), r.dueDate(), r.amountCents(), r.remainingCents())).toList());
    }

    private static List<ProposedRow> rows(List<InstalmentLine> lines) {
        return lines.stream().map(i -> new ProposedRow(i.seq(), i.dueDate(), i.amountCents(), i.remainingCents())).toList();
    }

    private static List<OwedRow> owed(List<InstalmentLine> lines) {
        return lines.stream().filter(i -> i.remainingCents() > 0).map(i -> new OwedRow(i.dueDate(), i.remainingCents())).toList();
    }
}
