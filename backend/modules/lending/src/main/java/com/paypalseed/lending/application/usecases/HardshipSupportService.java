package com.paypalseed.lending.application.usecases;

import com.paypalseed.engines.schedule.Aging;
import com.paypalseed.engines.schedule.Allocation.InstalmentAllocation;
import com.paypalseed.engines.schedule.Bucket;
import com.paypalseed.engines.schedule.Instalment;
import com.paypalseed.lending.api.dto.LendingDtos.OwedLine;
import com.paypalseed.lending.api.dto.LendingDtos.RiskFacts;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.infrastructure.persistence.AttemptRepository;
import com.paypalseed.lending.infrastructure.persistence.AttemptRepository.Status;
import com.paypalseed.lending.infrastructure.persistence.HardshipSupportRepository;
import com.paypalseed.lending.infrastructure.persistence.HardshipSupportRepository.Hold;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.lending.infrastructure.persistence.ProjectionRepository;
import com.paypalseed.lending.infrastructure.persistence.TransactionRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The facts F5 hardship pushes into lending (open-case flag, grace holds) and the facts it reads back
 * (risk facts, scan candidates). Lending never depends on hardship: the commands join the caller's
 * transaction, so a case row and the flag or hold it implies commit together.
 */
@Service
public class HardshipSupportService {

    static final int MAX_HOLD_DAYS_AFTER_DUE = 10;
    private static final int LATE_LOOKBACK_DAYS = 90;
    private static final int FAILED_CHARGE_LOOKBACK_DAYS = 14;

    private final LoanRepository loans;
    private final HardshipSupportRepository support;
    private final AttemptRepository attempts;
    private final ProjectionRepository projections;
    private final TransactionRepository transactions;
    private final LoanRecalculator recalculator;
    private final BusinessClock clock;

    public HardshipSupportService(LoanRepository loans, HardshipSupportRepository support, AttemptRepository attempts,
                                  ProjectionRepository projections, TransactionRepository transactions,
                                  LoanRecalculator recalculator, BusinessClock clock) {
        this.loans = loans;
        this.support = support;
        this.attempts = attempts;
        this.projections = projections;
        this.transactions = transactions;
        this.recalculator = recalculator;
        this.clock = clock;
    }

    /** Opening also cancels retries already planned: F4 AC3, no retry while a case is open. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void setHardshipOpen(UUID loanId, String caseRef, boolean open) {
        LoanRow loan = lockedLoan(loanId);
        if (open) {
            support.openFlag(loan.id(), caseRef, clock.today());
            attempts.cancelPlanned(loan.id(), "hardship case open");
        } else {
            support.closeFlag(loan.id(), caseRef);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void placeHold(UUID loanId, int seq, LocalDate until, String caseRef, String cause) {
        LoanRow loan = lockedLoan(loanId);
        LocalDate today = clock.today();
        LoanRecalculator.Projection now = recalculator.project(loan, List.of());
        Instalment inst = now.schedule().find(seq)
                .filter(i -> now.allocation().of(i.seq()).remainingCents() > 0)
                .orElseThrow(() -> new DomainException(LendingErrors.HOLD_NOT_APPLICABLE,
                        "Instalment " + seq + " is not owed in version " + now.schedule().version()));
        if (until.isBefore(today) || until.isAfter(inst.dueDate().plusDays(MAX_HOLD_DAYS_AFTER_DUE))) {
            throw new DomainException(LendingErrors.HOLD_OUT_OF_RANGE,
                    "Hold until " + until + " is outside " + today + ".." + inst.dueDate().plusDays(MAX_HOLD_DAYS_AFTER_DUE));
        }
        Optional<Hold> existing = support.unreleased(loan.id(), seq);
        if (existing.isPresent()) {
            Hold h = existing.get();
            if (h.until().isBefore(today) || h.version() != now.schedule().version()) {
                support.release(h.id(), today, "expired");
            } else if (!h.caseRef().equals(caseRef)) {
                throw new DomainException(LendingErrors.HOLD_CONFLICT, "Instalment " + seq + " is held by another case");
            } else if (h.until().equals(until)) {
                return; // replay
            } else {
                support.release(h.id(), today, "extended");
            }
        }
        support.insertHold(new Hold(Ids.newId(), loan.id(), now.schedule().version(), seq, inst.dueDate(), today, until,
                cause, caseRef, null));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseHold(String caseRef, String reason) {
        support.releaseCase(caseRef, clock.today(), reason);
    }

    @Transactional(readOnly = true)
    public Optional<RiskFacts> riskFacts(UUID loanId, LocalDate asOf) {
        return loans.find(loanId).map(loan -> {
            LoanRecalculator.Projection p = recalculator.project(loan, List.of());
            Aging aging = Aging.of(p.schedule(), p.allocation(), asOf, loan.policy());
            boolean active = loan.isActive();
            List<OwedLine> owed = p.schedule().instalments().stream()
                    .map(i -> {
                        InstalmentAllocation a = p.allocation().of(i.seq());
                        return new OwedLine(i.seq(), i.dueDate(), i.amountCents(), a.remainingCents());
                    })
                    .toList();
            Optional<OwedLine> target = owed.stream().filter(o -> o.remainingCents() > 0).findFirst();
            int version = p.schedule().version();
            boolean failedTwice = target.map(t -> attempts.forInstalment(loan.id(), version, t.seq()).stream()
                    .filter(a -> a.status() == Status.FAILED).count() >= 2).orElse(false);
            LocalDate lateFrom = asOf.minusDays(LATE_LOOKBACK_DAYS);
            boolean lateRecently = projections.transitions(loan.id()).stream()
                    .anyMatch(t -> !Bucket.CURRENT.name().equals(t.toBucket()) && !t.asOf().isBefore(lateFrom));
            return new RiskFacts(loan.id(), loan.memberId(), loan.status().name(),
                    active ? aging.bucket().name() : Bucket.CURRENT.name(), active ? aging.dpd() : 0, version,
                    loan.dueDay(), p.allocation().outstandingCents(),
                    target.map(OwedLine::seq).orElse(null), target.map(OwedLine::dueDate).orElse(null),
                    target.map(OwedLine::amountCents).orElse(null), target.map(OwedLine::remainingCents).orElse(null),
                    attempts.failedSince(loan.id(), asOf.minusDays(FAILED_CHARGE_LOOKBACK_DAYS - 1L)), failedTwice,
                    lateRecently, transactions.pendingCents(loan.id()), support.openCase(loan.id()).isPresent(), owed);
        });
    }

    @Transactional(readOnly = true)
    public List<UUID> scanCandidates(LocalDate asOf, int leadDays, int graceDays, int shardGroup, int shardCount,
                                     UUID after, int limit) {
        return loans.hardshipScanCandidates(asOf.minusDays(graceDays), asOf.plusDays(leadDays), shardGroup, shardCount,
                limit, after);
    }

    private LoanRow lockedLoan(UUID loanId) {
        return loans.lock(loanId).orElseThrow(() -> new DomainException(LendingErrors.LOAN_NOT_FOUND, "No loan " + loanId));
    }
}
