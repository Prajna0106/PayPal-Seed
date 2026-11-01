package com.paypalseed.reserve.application.usecases;

import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.ApprovalView;
import com.paypalseed.governance.api.dto.GovernanceDtos.Decide;
import com.paypalseed.governance.api.dto.GovernanceDtos.Decision;
import com.paypalseed.governance.api.dto.GovernanceDtos.DecisionResult;
import com.paypalseed.governance.api.dto.GovernanceDtos.OpenApproval;
import com.paypalseed.governance.api.dto.GovernanceDtos.Tier;
import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.ledger.api.dto.PostingRequest;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.reserve.api.dto.ReserveDtos.CoverDecisionResult;
import com.paypalseed.reserve.api.dto.ReserveDtos.CoverRequestPage;
import com.paypalseed.reserve.api.dto.ReserveDtos.CoverRequestView;
import com.paypalseed.reserve.api.dto.ReserveDtos.CoverStatus;
import com.paypalseed.reserve.api.dto.ReserveDtos.CreateCover;
import com.paypalseed.reserve.api.events.ReserveEvents.CoverApproved;
import com.paypalseed.reserve.api.events.ReserveEvents.CoverRejected;
import com.paypalseed.reserve.api.events.ReserveEvents.CoverRequested;
import com.paypalseed.reserve.domain.model.FundState;
import com.paypalseed.reserve.domain.model.RequestHashes;
import com.paypalseed.reserve.domain.model.ReserveErrors;
import com.paypalseed.reserve.infrastructure.persistence.CoverRepository;
import com.paypalseed.reserve.infrastructure.persistence.CoverRepository.CoverRow;
import com.paypalseed.reserve.infrastructure.persistence.FundRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cover requests (ADR 0019). An officer raises one for a member's loan; governance runs the maker-checker decision;
 * the second approval moves money in the books from the fund to the loan's receivable. The fund never pays a person.
 *
 * <p>Lock order is always cover row, then fund row, so a settlement and a new request cannot deadlock.
 */
@Service
public class CoverService {

    private static final String LOAN_ACTIVE = "ACTIVE";
    private static final int MAX_PAGE = 200;

    private final CoverRepository covers;
    private final FundRepository funds;
    private final LendingFacade lending;
    private final LedgerFacade ledger;
    private final GovernanceFacade governance;
    private final ReserveSettings settings;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public CoverService(CoverRepository covers, FundRepository funds, LendingFacade lending, LedgerFacade ledger,
                        GovernanceFacade governance, ReserveSettings settings, BusinessClock clock,
                        ApplicationEventPublisher events) {
        this.covers = covers;
        this.funds = funds;
        this.lending = lending;
        this.ledger = ledger;
        this.governance = governance;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public CoverRequestView create(UUID staffId, CreateCover command, String idempotencyKey) {
        if (command.amountCents() <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
        String hash = RequestHashes.of(command.memberId(), command.loanId(), command.amountCents(), command.reasonCode(),
                command.hardshipCaseId(), command.note());
        Optional<CoverRow> prior = covers.byKey(idempotencyKey);
        if (prior.isPresent()) {
            if (!prior.get().requestHash().equals(hash)) {
                throw new DomainException(CommonErrors.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was used for another request");
            }
            return view(prior.get());
        }
        // Held to the end of the transaction: serialises requests, so the yearly cap and the balance cannot be raced.
        FundState fund = funds.lock();
        LoanSummary loan = lending.loan(command.loanId()).filter(l -> l.memberId().equals(command.memberId()))
                .orElseThrow(() -> new DomainException(ReserveErrors.COVER_LOAN_NOT_FOUND, "No loan " + command.loanId()
                        + " for this member"));
        requireCoverable(loan, command.amountCents());
        LocalDate today = clock.today();
        LocalDate yearStart = LocalDate.of(today.getYear(), 1, 1);
        settings.coverPolicy().check(command.amountCents(),
                covers.committed(command.memberId(), yearStart, yearStart.plusYears(1)));
        requireFunds(fund, command.amountCents());
        if (covers.hasOpenFor(command.loanId())) {
            throw new DomainException(ReserveErrors.COVER_REQUEST_OPEN, "Loan " + command.loanId() + " already has an open request");
        }
        UUID id = Ids.newId();
        covers.insert(new CoverRow(id, command.memberId(), command.loanId(), command.amountCents(), command.reasonCode(),
                command.note(), command.hardshipCaseId(), CoverStatus.PENDING_APPROVAL, null, staffId, today, idempotencyKey,
                hash, null, null));
        UUID approval = governance.openApprovalRequest(new OpenApproval(id, command.memberId(), Tier.MAKER_CHECKER, null,
                snapshotHash(id, command.memberId(), command.loanId(), command.amountCents()),
                clock.now().plus(settings.decisionSla()), true));
        covers.setApprovalRequest(id, approval);
        events.publishEvent(new CoverRequested(Ids.newId(), id, command.memberId(), command.loanId(), command.amountCents(), today));
        return view(covers.find(id).orElseThrow());
    }

    /**
     * An officer's decision. The maker or checker role follows from the approval request; governance enforces two
     * different people and the roles. The decision that closes the request as approved also pays.
     */
    @Transactional
    public CoverDecisionResult decide(UUID coverId, UUID staffId, Set<String> roles, Decision decision, String reasonCode,
                                      String note, long evidenceViewedMs, String idempotencyKey) {
        CoverRow cover = covers.lock(coverId).orElseThrow(() -> new DomainException(ReserveErrors.COVER_REQUEST_NOT_FOUND,
                "No cover request " + coverId));
        if (cover.approvalRequestId() == null) {
            throw new DomainException(ReserveErrors.COVER_NOT_AWAITING_DECISION, "Cover request " + coverId + " has no approval");
        }
        boolean pending = cover.status() == CoverStatus.PENDING_APPROVAL;
        if (pending && decision == Decision.APPROVE) {
            // Refuse before the decision is recorded: a signature on money the fund does not hold would be a lie.
            requireFunds(funds.lock(), cover.amountCents());
            lending.loan(cover.loanId()).ifPresentOrElse(l -> requireCoverable(l, cover.amountCents()), () -> {
                throw new DomainException(ReserveErrors.COVER_LOAN_NOT_FOUND, "No loan " + cover.loanId());
            });
        }
        DecisionResult result = governance.decide(new Decide(cover.approvalRequestId(), staffId, roles, null, decision,
                reasonCode, note, evidenceViewedMs, snapshotHash(cover.id(), cover.memberId(), cover.loanId(), cover.amountCents()),
                idempotencyKey));
        CoverStatus status = cover.status();
        if (pending) {
            if ("APPROVED".equals(result.requestStatus())) {
                status = settle(cover);
            } else if ("REJECTED".equals(result.requestStatus())) {
                status = reject(cover, reasonCode);
            }
        }
        return new CoverDecisionResult(result.requestId(), result.requestStatus(), result.role(), result.next(),
                result.auditSeq(), status);
    }

    /**
     * Governance closed an approval request: brings the cover request to the same end. Covers a decision made
     * without going through {@link #decide}. A no-op unless the cover request is still waiting.
     */
    @Transactional
    public void onApprovalClosed(UUID approvalRequestId) {
        Optional<CoverRow> found = covers.byApprovalRequest(approvalRequestId);
        if (found.isEmpty()) {
            return;
        }
        CoverRow cover = covers.lock(found.get().id()).orElseThrow();
        if (cover.status() != CoverStatus.PENDING_APPROVAL) {
            return;
        }
        Optional<ApprovalView> approval = governance.request(approvalRequestId);
        if (approval.isEmpty()) {
            return;
        }
        switch (approval.get().status()) {
            case "APPROVED" -> settle(cover);
            case "REJECTED", "VOIDED", "EXPIRED" -> reject(cover,
                    approval.get().closedReason() == null ? approval.get().status() : approval.get().closedReason());
            default -> {
                // Still open (the maker has signed, the checker has not): nothing to do yet.
            }
        }
    }

    @Transactional(readOnly = true)
    public CoverRequestPage page(List<CoverStatus> statuses, UUID before, int limit) {
        int size = Math.clamp(limit, 1, MAX_PAGE);
        List<CoverRow> rows = covers.page(statuses == null ? List.of() : statuses, before, size + 1);
        boolean more = rows.size() > size;
        List<CoverRow> shown = more ? rows.subList(0, size) : rows;
        return new CoverRequestPage(shown.stream().map(CoverService::view).toList(),
                more ? shown.get(shown.size() - 1).id() : null);
    }

    /** Pays the loan in the books. The cover row is locked by the caller. */
    private CoverStatus settle(CoverRow cover) {
        FundState fund = funds.lock();
        Optional<LoanSummary> loan = lending.loan(cover.loanId());
        if (!fund.canCover(cover.amountCents())) {
            return fail(cover, "RESERVE_INSUFFICIENT_FUNDS");
        }
        if (loan.isEmpty() || !LOAN_ACTIVE.equals(loan.get().status()) || cover.amountCents() > loan.get().outstandingCents()) {
            return fail(cover, "LOAN_CHANGED");
        }
        LocalDate today = clock.today();
        String sourceKey = approvalRef(cover.id());
        UUID entry = ledger.post(PostingRequest.builder(sourceKey, "Reserve cover for loan " + cover.loanId(), today)
                .debit(AccountRef.program(AccountType.LOSS_RESERVE), cover.amountCents())
                .credit(AccountRef.of(AccountType.LOANS_RECEIVABLE, cover.loanId()), cover.amountCents())
                .build());
        funds.save(fund.payOut(cover.amountCents(), funds.markCovered(cover.memberId(), today)));
        covers.markApproved(cover.id(), entry);
        events.publishEvent(new CoverApproved(Ids.newId(), cover.id(), cover.loanId(), cover.memberId(), cover.amountCents(),
                entry, sourceKey, today));
        return CoverStatus.APPROVED;
    }

    private CoverStatus reject(CoverRow cover, String reason) {
        covers.markClosed(cover.id(), CoverStatus.REJECTED, reason);
        events.publishEvent(new CoverRejected(Ids.newId(), cover.id(), cover.loanId(), cover.memberId(), reason, clock.today()));
        return CoverStatus.REJECTED;
    }

    /** Approved by people but impossible now: recorded, visible to the desk, nothing posted. */
    private CoverStatus fail(CoverRow cover, String reason) {
        covers.markClosed(cover.id(), CoverStatus.FAILED, reason);
        events.publishEvent(new CoverRejected(Ids.newId(), cover.id(), cover.loanId(), cover.memberId(), reason, clock.today()));
        return CoverStatus.FAILED;
    }

    private static void requireCoverable(LoanSummary loan, long amountCents) {
        if (!LOAN_ACTIVE.equals(loan.status())) {
            throw new DomainException(ReserveErrors.COVER_LOAN_NOT_ACTIVE, "Loan " + loan.loanId() + " is " + loan.status());
        }
        if (amountCents > loan.outstandingCents()) {
            throw new DomainException(ReserveErrors.COVER_EXCEEDS_OUTSTANDING,
                    "The loan owes " + loan.outstandingCents() + " cents", Map.of("outstandingCents", loan.outstandingCents()));
        }
    }

    private static void requireFunds(FundState fund, long amountCents) {
        if (!fund.canCover(amountCents)) {
            throw new DomainException(ReserveErrors.RESERVE_INSUFFICIENT_FUNDS,
                    "The reserve holds " + fund.balanceCents() + " cents; the cover needs " + amountCents,
                    Map.of("balanceCents", fund.balanceCents(), "requestedCents", amountCents));
        }
    }

    /** What the officers approve: the member, the loan and the amount. A different cover is a different request. */
    static String snapshotHash(UUID coverId, UUID memberId, UUID loanId, long amountCents) {
        return RequestHashes.of("reserve-cover", coverId, memberId, loanId, amountCents);
    }

    static String approvalRef(UUID coverId) {
        return "reserve-cover:" + coverId;
    }

    static CoverRequestView view(CoverRow c) {
        return new CoverRequestView(c.id(), c.memberId(), c.loanId(), c.amountCents(), c.reasonCode(), c.hardshipCaseId(),
                c.status(), c.approvalRequestId(), c.requestedOn(), c.closedReason(), c.journalEntryId());
    }
}
