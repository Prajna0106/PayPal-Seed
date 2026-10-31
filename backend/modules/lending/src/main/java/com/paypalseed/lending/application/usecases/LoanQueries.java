package com.paypalseed.lending.application.usecases;

import com.paypalseed.engines.schedule.Allocation;
import com.paypalseed.engines.schedule.Allocation.InstalmentAllocation;
import com.paypalseed.engines.schedule.InstalmentStatus;
import com.paypalseed.engines.schedule.MandateReconciliation.CollectionSpec;
import com.paypalseed.engines.schedule.MandateReconciliation.DueAmount;
import com.paypalseed.engines.schedule.Schedule;
import com.paypalseed.engines.schedule.ScheduleDiff;
import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.lending.api.dto.LendingDtos.InstalmentLine;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleDiffView;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.lending.api.dto.LendingDtos.Slot;
import com.paypalseed.lending.api.dto.LendingDtos.VersionView;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.CollectionMode;
import com.paypalseed.lending.domain.model.LoanTransaction;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.lending.infrastructure.persistence.ProjectionRepository;
import com.paypalseed.lending.infrastructure.persistence.ScheduleRepository;
import com.paypalseed.lending.infrastructure.persistence.ScheduleRepository.StoredVersion;
import com.paypalseed.lending.infrastructure.persistence.TransactionRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read models for the member app, the desk, agents and the mandate reconciler. No writes. */
@Service
@Transactional(readOnly = true)
public class LoanQueries {

    /** INV-2 / INV-8 for one loan: the books and the derived state must agree. */
    public record Integrity(UUID loanId, long outstandingCents, long ledgerReceivableCents, long overpaidCents,
                            long memberOverpaidCents, long ledgerMemberOverpaymentCents, boolean consistent) {
    }

    public record TransactionView(UUID id, String type, String source, String paypalTxnId, long amountCents,
                                  long feeCents, LocalDate effectiveDate) {
    }

    private final LoanRepository loans;
    private final ScheduleRepository schedules;
    private final TransactionRepository transactions;
    private final ProjectionRepository projections;
    private final LoanRecalculator recalculator;
    private final LedgerFacade ledger;
    private final BusinessClock clock;

    public LoanQueries(LoanRepository loans, ScheduleRepository schedules, TransactionRepository transactions,
                       ProjectionRepository projections, LoanRecalculator recalculator, LedgerFacade ledger,
                       BusinessClock clock) {
        this.loans = loans;
        this.schedules = schedules;
        this.transactions = transactions;
        this.projections = projections;
        this.recalculator = recalculator;
        this.ledger = ledger;
        this.clock = clock;
    }

    public LoanRow loan(UUID loanId) {
        return loans.find(loanId).orElseThrow(() -> new DomainException(LendingErrors.LOAN_NOT_FOUND, "No loan " + loanId));
    }

    /** Resolves a member's loan; any other member's loan is reported as not found (no enumeration). */
    public LoanRow loanOfMember(UUID loanId, UUID memberId) {
        LoanRow loan = loan(loanId);
        if (!loan.memberId().equals(memberId)) {
            throw new DomainException(LendingErrors.LOAN_NOT_FOUND, "No loan " + loanId);
        }
        return loan;
    }

    public List<LoanSummary> loansOfMember(UUID memberId) {
        return loans.byMember(memberId).stream().map(LoanQueries::summary).toList();
    }

    public static LoanSummary summary(LoanRow l) {
        return new LoanSummary(l.id(), l.memberId(), l.principalCents(), l.outstandingCents(), l.status().name(),
                l.collectionMode().name(), l.bucket().name(), l.dpd(), l.currentVersion());
    }

    /** The current version with the stored allocation, or an older version re-folded on the same history. */
    public ScheduleView schedule(LoanRow loan, Optional<Integer> version) {
        int v = version.orElse(loan.currentVersion());
        Schedule schedule = schedules.load(loan.id(), v, loan.dueDay())
                .orElseThrow(() -> new DomainException(LendingErrors.SCHEDULE_VERSION_NOT_FOUND, "No version " + v));
        Allocation allocation = recalculator.project(loanWithVersion(loan, v), List.of()).allocation();
        return view(loan, schedule, allocation, clock.today());
    }

    public ScheduleView view(LoanRow loan, Schedule schedule, Allocation allocation, LocalDate asOf) {
        List<InstalmentLine> lines = new ArrayList<>();
        schedule.instalments().forEach(i -> {
            InstalmentAllocation a = allocation.of(i.seq());
            lines.add(new InstalmentLine(i.seq(), i.dueDate(), i.amountCents(), a.paidCents(), a.remainingCents(),
                    InstalmentStatus.of(i, a, asOf, loan.policy()).name(), a.fullyPaidOn()));
        });
        var aging = com.paypalseed.engines.schedule.Aging.of(schedule, allocation, asOf, loan.policy());
        return new ScheduleView(loan.id(), schedule.version(), schedule.reason().name(), asOf, schedule.principalCents(),
                allocation.outstandingCents(), allocation.overpaymentCents(), loan.status().name(),
                loan.collectionMode().name(), loan.isActive() ? aging.dpd() : 0,
                loan.isActive() ? aging.bucket().name() : "CURRENT", lines);
    }

    public List<VersionView> versions(LoanRow loan) {
        List<StoredVersion> stored = schedules.versions(loan.id(), loan.dueDay());
        List<VersionView> out = new ArrayList<>();
        for (int k = 0; k < stored.size(); k++) {
            StoredVersion sv = stored.get(k);
            Optional<ScheduleDiffView> diff = k == 0 ? Optional.empty()
                    : Optional.of(diff(ScheduleDiff.between(stored.get(k - 1).schedule(), sv.schedule())));
            out.add(new VersionView(sv.schedule().version(), sv.schedule().reason().name(), sv.causedBy(), sv.createdOn(),
                    slots(sv.schedule()), diff));
        }
        return out;
    }

    public List<TransactionView> transactions(LoanRow loan) {
        return transactions.forLoan(loan.id()).stream()
                .map(t -> new TransactionView(t.id(), t.type().name(), t.source().name(), t.paypalTxnId(), t.amountCents(),
                        t.feeCents(), t.effectiveDate()))
                .toList();
    }

    public List<LoanTransaction> transactionsOf(UUID loanId) {
        return transactions.forLoan(loanId);
    }

    public List<LoanTransaction> receiptsNewestFirst(UUID loanId) {
        List<LoanTransaction> receipts = new ArrayList<>(transactions.forLoan(loanId).stream()
                .filter(t -> t.type() == LoanTransaction.Type.RECEIPT).toList());
        java.util.Collections.reverse(receipts);
        return receipts;
    }

    /** What autopay should collect (input to the mandate reconciler, ADR 0005). */
    public CollectionSpec collectionSpec(UUID loanId) {
        LoanRow loan = loan(loanId);
        boolean collectible = loan.isActive() && loan.collectionMode() == CollectionMode.AUTOPAY && loan.outstandingCents() > 0;
        Schedule schedule = schedules.loadCurrent(loan.id(), loan.currentVersion(), loan.dueDay());
        var allocation = projections.allocation(loanId);
        List<DueAmount> unpaid = schedule.instalments().stream()
                .filter(i -> allocation.containsKey(i.seq()) && allocation.get(i.seq()).remainingCents() > 0)
                .map(i -> new DueAmount(i.dueDate(), allocation.get(i.seq()).remainingCents()))
                .toList();
        return new CollectionSpec(collectible, unpaid);
    }

    public Integrity integrity(UUID loanId) {
        LoanRow loan = loan(loanId);
        long receivable = ledger.balanceCents(AccountRef.of(AccountType.LOANS_RECEIVABLE, loanId));
        long memberOverpaid = loans.byMember(loan.memberId()).stream().mapToLong(LoanRow::overpaidCents).sum();
        long ledgerOverpaid = ledger.balanceCents(AccountRef.of(AccountType.MEMBER_OVERPAYMENT, loan.memberId()));
        Allocation refolded = recalculator.project(loan, List.of()).allocation();
        boolean consistent = receivable == loan.outstandingCents() && memberOverpaid == ledgerOverpaid
                && refolded.outstandingCents() == loan.outstandingCents()
                && refolded.overpaymentCents() == loan.overpaidCents();
        return new Integrity(loanId, loan.outstandingCents(), receivable, loan.overpaidCents(), memberOverpaid,
                ledgerOverpaid, consistent);
    }

    static ScheduleDiffView diff(ScheduleDiff d) {
        return new ScheduleDiffView(
                d.removed().stream().map(s -> new Slot(s.seq(), s.dueDate(), s.amountCents())).toList(),
                d.added().stream().map(s -> new Slot(s.seq(), s.dueDate(), s.amountCents())).toList());
    }

    static List<Slot> slots(Schedule s) {
        return s.instalments().stream().map(i -> new Slot(i.seq(), i.dueDate(), i.amountCents())).toList();
    }

    private static LoanRow loanWithVersion(LoanRow l, int version) {
        return new LoanRow(l.id(), l.memberId(), l.principalCents(), l.instalmentCount(), l.dueDay(), l.disbursedOn(),
                l.policyVersion(), l.status(), l.collectionMode(), version, l.outstandingCents(), l.overpaidCents(), l.dpd(),
                l.bucket(), l.bucketSince(), l.lastClosedOn(), l.closedOn(), l.rowVersion());
    }
}
