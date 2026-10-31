package com.paypalseed.lending.application.usecases;

import com.paypalseed.engines.schedule.Allocation.InstalmentAllocation;
import com.paypalseed.engines.schedule.Instalment;
import com.paypalseed.engines.schedule.TimerPlanner.Kind;
import com.paypalseed.engines.schedule.TimerPlanner.Timer;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.lending.application.usecases.LoanRecalculator.Outcome;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.CollectionMode;
import com.paypalseed.lending.infrastructure.persistence.AttemptRepository;
import com.paypalseed.lending.infrastructure.persistence.AttemptRepository.Status;
import com.paypalseed.lending.infrastructure.persistence.CobRepository;
import com.paypalseed.lending.infrastructure.persistence.CobRepository.CobRun;
import com.paypalseed.lending.infrastructure.persistence.HardshipSupportRepository;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.lending.infrastructure.persistence.TimerRepository;
import com.paypalseed.lending.infrastructure.persistence.TransactionRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Close of business for one business date (03-algorithms §7, ADR 0007; Fineract's Loan COB).
 *
 * <ul>
 *   <li>Dates are closed strictly in order; a date is never skipped and never closed twice.</li>
 *   <li>Each loan is closed in its own transaction: re-age it, fire its due timers, mark them fired.
 *       A crash re-fires only what had not committed, so effects happen exactly once.</li>
 *   <li>{@code TIMERS} mode visits only loans with a timer due (O(changes)); {@code SCAN} mode
 *       visits every active loan not yet closed for the date. Same per-loan logic; scan is the
 *       fallback and the test oracle for the timer index.</li>
 * </ul>
 */
@Service
public class CloseOfBusiness {

    private static final Logger log = LoggerFactory.getLogger(CloseOfBusiness.class);
    private static final Pattern INSTALMENT_REF = Pattern.compile("^v(\\d+):s(\\d+)");
    private static final int BATCH = 200;

    public enum Mode {
        TIMERS,
        SCAN
    }

    public enum LoanResult {
        CLOSED,
        SKIPPED_LOCKED,
        FAILED
    }

    public record ShardResult(LocalDate businessDate, int shard, boolean done, int processed, int skipped, int failed) {
    }

    private final LoanRepository loans;
    private final TimerRepository timers;
    private final AttemptRepository attempts;
    private final TransactionRepository transactions;
    private final CobRepository cob;
    private final HardshipSupportRepository hardship;
    private final LoanRecalculator recalculator;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate perLoan;
    private final Mode mode;
    private final int shardCount;

    public CloseOfBusiness(LoanRepository loans, TimerRepository timers, AttemptRepository attempts,
                           TransactionRepository transactions, CobRepository cob, HardshipSupportRepository hardship,
                           LoanRecalculator recalculator,
                           BusinessClock clock, ApplicationEventPublisher events, PlatformTransactionManager txManager,
                           @Value("${seed.cob.mode:TIMERS}") Mode mode, @Value("${seed.cob.shard-count:8}") int shardCount) {
        this.loans = loans;
        this.timers = timers;
        this.attempts = attempts;
        this.transactions = transactions;
        this.cob = cob;
        this.hardship = hardship;
        this.recalculator = recalculator;
        this.clock = clock;
        this.events = events;
        this.perLoan = new TransactionTemplate(txManager);
        this.perLoan.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.mode = mode;
        if (shardCount < 1 || 64 % shardCount != 0) {
            throw new IllegalArgumentException("seed.cob.shard-count must divide 64");
        }
        this.shardCount = shardCount;
    }

    public CobRun start(LocalDate date, String triggerRef) {
        if (date.isAfter(clock.today())) {
            throw new DomainException(LendingErrors.COB_IN_THE_FUTURE, "Business date is " + clock.today());
        }
        Optional<CobRun> existing = cob.find(date);
        if (existing.isPresent()) {
            return existing.get();
        }
        Optional<CobRun> latest = cob.latest();
        if (latest.isPresent()) {
            LocalDate expected = latest.get().businessDate().plusDays(1);
            if (!date.equals(expected) || "RUNNING".equals(latest.get().status())) {
                throw new DomainException(LendingErrors.COB_OUT_OF_ORDER,
                        "Next close of business must be " + expected + " after " + latest.get().businessDate() + " finishes",
                        Map.of("expected", expected.toString(), "latestStatus", latest.get().status()));
            }
        }
        cob.start(date, mode.name(), shardCount, triggerRef);
        return cob.find(date).orElseThrow();
    }

    /** Closes up to {@code maxLoans} loans of one shard; call again until {@code done}. */
    public ShardResult processShard(LocalDate date, int shard, int maxLoans) {
        CobRun run = cob.find(date).orElseThrow(() -> new DomainException(LendingErrors.COB_RUN_NOT_FOUND, "No run for " + date));
        if (shard < 0 || shard >= run.shardCount()) {
            throw new IllegalArgumentException("shard must be 0.." + (run.shardCount() - 1));
        }
        if (!"RUNNING".equals(run.status())) {
            return new ShardResult(date, shard, true, 0, 0, 0);
        }
        Mode runMode = Mode.valueOf(run.mode());
        UUID after = null;
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        boolean done = false;
        while (processed + skipped + failed < maxLoans) {
            List<UUID> ids = nextBatch(runMode, date, shard, run.shardCount(), Math.min(BATCH, maxLoans), after);
            if (ids.isEmpty()) {
                done = true;
                break;
            }
            for (UUID id : ids) {
                switch (closeLoan(id, date, true)) {
                    case CLOSED -> processed++;
                    case SKIPPED_LOCKED -> skipped++;
                    case FAILED -> failed++;
                }
            }
            after = ids.get(ids.size() - 1);
        }
        cob.addCounts(date, processed, failed);
        return new ShardResult(date, shard, done && skipped == 0, processed, skipped, failed);
    }

    /** Waits for loans skipped because they were locked, then marks the run COMPLETED or DEGRADED. */
    public CobRun finish(LocalDate date) {
        CobRun run = cob.find(date).orElseThrow(() -> new DomainException(LendingErrors.COB_RUN_NOT_FOUND, "No run for " + date));
        if (!"RUNNING".equals(run.status())) {
            return run;
        }
        int failed = 0;
        int processed = 0;
        for (int shard = 0; shard < run.shardCount(); shard++) {
            UUID after = null;
            List<UUID> ids;
            while (!(ids = nextBatch(Mode.valueOf(run.mode()), date, shard, run.shardCount(), BATCH, after)).isEmpty()) {
                for (UUID id : ids) {
                    if (closeLoan(id, date, false) == LoanResult.CLOSED) {
                        processed++;
                    } else {
                        failed++;
                    }
                }
                after = ids.get(ids.size() - 1);
            }
        }
        cob.addCounts(date, processed, failed);
        CobRun counted = cob.find(date).orElseThrow();
        String status = counted.loansFailed() > 0 ? "DEGRADED" : "COMPLETED";
        cob.finish(date, status);
        log.info("Close of business {} {}: {} loans, {} failed", date, status, counted.loansProcessed(), counted.loansFailed());
        return cob.find(date).orElseThrow();
    }

    /** Closes every date after the last closed one, up to and including {@code target}, in order. */
    public List<CobRun> catchUp(LocalDate target, String triggerRef) {
        List<CobRun> runs = new ArrayList<>();
        Optional<CobRun> latest = cob.latest();
        if (latest.isPresent() && "RUNNING".equals(latest.get().status())) {
            runs.add(runToCompletion(latest.get().businessDate(), triggerRef));
        }
        LocalDate next = cob.latest().map(r -> r.businessDate().plusDays(1)).orElse(target);
        for (LocalDate d = next; !d.isAfter(target); d = d.plusDays(1)) {
            runs.add(runToCompletion(d, triggerRef));
        }
        return runs;
    }

    private CobRun runToCompletion(LocalDate date, String triggerRef) {
        CobRun run = start(date, triggerRef);
        for (int shard = 0; shard < run.shardCount(); shard++) {
            ShardResult r;
            do {
                r = processShard(date, shard, 10_000);
            } while (!r.done() && r.processed() + r.failed() > 0);
        }
        return finish(date);
    }

    /** Keyset pages in loan id order: a loan that was skipped or failed is passed over, not retried in a loop. */
    private List<UUID> nextBatch(Mode m, LocalDate date, int shard, int count, int limit, UUID after) {
        return m == Mode.TIMERS
                ? timers.loansWithDueTimers(date, shard, count, limit, after)
                : loans.activeNotClosed(date, shard, count, limit, after);
    }

    /** One loan, one transaction. Exceptions roll back this loan only and are counted, never rethrown. */
    LoanResult closeLoan(UUID loanId, LocalDate date, boolean skipLocked) {
        try {
            return perLoan.execute(status -> {
                Optional<LoanRow> locked = skipLocked ? loans.tryLock(loanId) : loans.lock(loanId);
                if (locked.isEmpty()) {
                    return LoanResult.SKIPPED_LOCKED;
                }
                Outcome outcome = recalculator.recalculate(locked.get(), date, LoanRecalculator.Cause.COB);
                boolean hardshipOpen = hardship.openCase(loanId).isPresent();
                for (Timer timer : timers.due(loanId, date)) {
                    if (graceExpiryHeld(timer, outcome, date)) {
                        continue; // stays due, so each close of business looks again until the hold ends
                    }
                    fire(timer, outcome, date, hardshipOpen);
                    timers.markFired(loanId, timer, date);
                }
                loans.setLastClosedOn(loanId, date);
                return LoanResult.CLOSED;
            });
        } catch (RuntimeException e) {
            log.error("Close of business {} failed for loan {}", date, loanId, e);
            return LoanResult.FAILED;
        }
    }

    /** An F5 grace hold keeps the miss unwritten; the timer must stay due until the hold ends. */
    private boolean graceExpiryHeld(Timer timer, Outcome o, LocalDate date) {
        if (timer.kind() != Kind.GRACE_EXPIRY) {
            return false;
        }
        Matcher m = INSTALMENT_REF.matcher(timer.ref());
        return m.find() && recalculator.isHeld(o.after(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), date);
    }

    private void fire(Timer timer, Outcome o, LocalDate date, boolean hardshipOpen) {
        LoanRow loan = o.after();
        switch (timer.kind()) {
            case REMIND_DUE_SOON -> currentInstalment(timer, o)
                    .filter(i -> date.isBefore(i.dueDate()))
                    .ifPresent(i -> events.publishEvent(new LendingEvents.InstalmentDueSoon(Ids.newId(), loan.id(),
                            loan.memberId(), date, o.schedule().version(), i.seq(), i.dueDate(), remaining(o, i),
                            loan.collectionMode().name())));
            case GRACE_NUDGE -> currentInstalment(timer, o)
                    .filter(i -> !hardshipOpen) // the open case already talks to the member (F4 AC3)
                    .filter(i -> {
                        long dpd = ChronoUnit.DAYS.between(i.dueDate(), date);
                        return dpd >= 1 && dpd <= loan.policy().graceDays() && transactions.pendingCents(loan.id()) == 0;
                    })
                    .ifPresent(i -> events.publishEvent(new LendingEvents.GraceNudgeDue(Ids.newId(), loan.id(),
                            loan.memberId(), date, o.schedule().version(), i.seq(), i.dueDate(), o.aging().overdueCents())));
            case WATCHDOG -> currentInstalment(timer, o)
                    .filter(i -> loan.collectionMode() == CollectionMode.AUTOPAY)
                    .ifPresent(i -> events.publishEvent(new LendingEvents.CollectionCheckRequested(Ids.newId(), loan.id(),
                            loan.memberId(), date, i.dueDate())));
            case RETRY_CAPTURE -> fireRetry(timer, o, date, hardshipOpen);
            case GRACE_EXPIRY, BUCKET_BOUNDARY -> {
                // the recalculation that just ran re-aged the loan and recorded any missed instalment
            }
        }
    }

    private void fireRetry(Timer timer, Outcome o, LocalDate date, boolean hardshipOpen) {
        UUID attemptId = UUID.fromString(timer.ref().substring("attempt:".length()));
        attempts.find(attemptId).filter(a -> a.status() == Status.PLANNED).ifPresent(a -> {
            boolean owed = a.version() == o.schedule().version() && o.schedule().find(a.seq()).isPresent()
                    && o.allocation().of(a.seq()).remainingCents() > 0;
            boolean autopay = o.after().isActive() && o.after().collectionMode() == CollectionMode.AUTOPAY;
            if (hardshipOpen) {
                attempts.setStatus(a.id(), Status.CANCELLED, "hardship case open");
                return;
            }
            if (!owed || !autopay || transactions.pendingCents(o.after().id()) > 0) {
                attempts.setStatus(a.id(), Status.CANCELLED, owed ? "no longer applicable" : "paid another way");
                return;
            }
            attempts.setStatus(a.id(), Status.REQUESTED, null);
            events.publishEvent(new LendingEvents.RetryCaptureRequested(Ids.newId(), o.after().id(), o.after().memberId(),
                    date, a.id(), o.allocation().of(a.seq()).remainingCents()));
        });
    }

    /** The instalment a timer names, if it is still in the current version and still owed. */
    private static Optional<Instalment> currentInstalment(Timer timer, Outcome o) {
        Matcher m = INSTALMENT_REF.matcher(timer.ref());
        if (!m.find() || Integer.parseInt(m.group(1)) != o.schedule().version()) {
            return Optional.empty();
        }
        return o.schedule().find(Integer.parseInt(m.group(2)))
                .filter(i -> o.allocation().of(i.seq()).remainingCents() > 0);
    }

    private static long remaining(Outcome o, Instalment i) {
        InstalmentAllocation a = o.allocation().of(i.seq());
        return a.remainingCents();
    }
}
