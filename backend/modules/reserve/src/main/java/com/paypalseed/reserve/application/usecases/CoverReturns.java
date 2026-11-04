package com.paypalseed.reserve.application.usecases;

import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.AuditCommand;
import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.ledger.api.dto.PostingRequest;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.reserve.api.dto.ReserveDtos.CoverReturn;
import com.paypalseed.reserve.api.dto.ReserveDtos.CoverStatus;
import com.paypalseed.reserve.api.events.ReserveEvents.CoverReturned;
import com.paypalseed.reserve.domain.model.FundState;
import com.paypalseed.reserve.domain.model.ReserveErrors;
import com.paypalseed.reserve.infrastructure.persistence.CoverRepository;
import com.paypalseed.reserve.infrastructure.persistence.CoverRepository.CoverRoom;
import com.paypalseed.reserve.infrastructure.persistence.CoverRepository.CoverRow;
import com.paypalseed.reserve.infrastructure.persistence.CoverReturnRepository;
import com.paypalseed.reserve.infrastructure.persistence.CoverReturnRepository.ReturnRow;
import com.paypalseed.reserve.infrastructure.persistence.FundRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money that goes back to the fund after a cover paid it out (ADR 0024). The ledger is only ever corrected by a new
 * entry; the fund's counters follow in the same transaction, so the books, the fund row and the public figure agree.
 *
 * <p>Lock order is the cover row(s), then the fund row, as in {@link CoverService}.
 */
@Service
public class CoverReturns {

    static final String UNUSED_COVER = "UNUSED_COVER";
    static final String LOAN_VOIDED = "LOAN_VOIDED";

    private static final Logger log = LoggerFactory.getLogger(CoverReturns.class);
    private static final String APPROVAL_PREFIX = "reserve-cover:";
    private static final Pattern REASON = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    private final CoverRepository covers;
    private final CoverReturnRepository returns;
    private final FundRepository funds;
    private final LedgerFacade ledger;
    private final GovernanceFacade governance;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public CoverReturns(CoverRepository covers, CoverReturnRepository returns, FundRepository funds, LedgerFacade ledger,
                        GovernanceFacade governance, BusinessClock clock, ApplicationEventPublisher events) {
        this.covers = covers;
        this.returns = returns;
        this.funds = funds;
        this.ledger = ledger;
        this.governance = governance;
        this.clock = clock;
        this.events = events;
    }

    /**
     * A cover credited the loan's receivable for more than the schedule took. Reverses that part in the ledger (debit
     * the receivable, credit the fund) and puts it back in the fund's counters. Idempotent on {@code approvalRef}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CoverReturn returnUnused(String approvalRef, long amountCents, String reasonCode) {
        if (amountCents <= 0 || reasonCode == null || !REASON.matcher(reasonCode).matches()) {
            throw new DomainException(ReserveErrors.COVER_RETURN_INVALID, "A return needs a positive amount and a reason code");
        }
        UUID coverId = coverIdOf(approvalRef);
        String returnRef = "reserve-cover-return:" + approvalRef;
        Optional<ReturnRow> earlier = returns.find(returnRef);
        if (earlier.isPresent()) {
            return replay(earlier.get(), amountCents);
        }
        CoverRow cover = covers.lock(coverId)
                .orElseThrow(() -> new DomainException(ReserveErrors.COVER_REQUEST_NOT_FOUND, "No cover " + coverId));
        earlier = returns.find(returnRef); // a concurrent delivery won the race while we waited for the lock
        if (earlier.isPresent()) {
            return replay(earlier.get(), amountCents);
        }
        if (cover.status() != CoverStatus.APPROVED) {
            throw new DomainException(ReserveErrors.COVER_RETURN_INVALID, "Cover " + coverId + " was never paid");
        }
        if (amountCents > cover.amountCents() - covers.returnedCents(coverId)) {
            throw new DomainException(ReserveErrors.COVER_RETURN_EXCEEDS_COVER,
                    "Cover " + coverId + " paid " + cover.amountCents() + " cents; " + amountCents + " cannot come back");
        }
        FundState fund = funds.lock();
        LocalDate today = clock.today();
        UUID entry = ledger.post(PostingRequest.builder(returnRef, "Unused reserve cover returned for loan " + cover.loanId(), today)
                .debit(AccountRef.of(AccountType.LOANS_RECEIVABLE, cover.loanId()), amountCents)
                .credit(AccountRef.program(AccountType.LOSS_RESERVE), amountCents)
                .build());
        funds.save(fund.returnCover(amountCents));
        covers.addReturned(coverId, amountCents);
        returns.insert(new ReturnRow(returnRef, UNUSED_COVER, cover.loanId(), amountCents, entry), reasonCode, today);
        events.publishEvent(new CoverReturned(Ids.newId(), returnRef, UNUSED_COVER, cover.loanId(), amountCents, entry,
                reasonCode, today));
        audit(coverId, returnRef, UNUSED_COVER, cover.loanId(), amountCents, reasonCode);
        log.warn("Returned {} unused cents of cover {} for loan {} to the fund", amountCents, coverId, cover.loanId());
        return new CoverReturn(returnRef, cover.loanId(), amountCents, entry, false);
    }

    /**
     * The loan was voided, and lending's own ledger entry already credited {@code amountCents} to the fund. Brings the
     * fund's counters and the covers of the loan in line, in the caller's transaction. Idempotent on the loan.
     */
    @Transactional
    public void onLoanVoided(UUID loanId, long amountCents) {
        if (amountCents <= 0) {
            return;
        }
        String returnRef = "loan-void:" + loanId;
        if (returns.find(returnRef).isPresent()) {
            return;
        }
        List<CoverRoom> rooms = covers.lockApprovedFor(loanId);
        if (returns.find(returnRef).isPresent()) {
            return; // a concurrent delivery won the race while we waited for the locks
        }
        long left = amountCents;
        for (CoverRoom room : rooms) {
            long take = Math.min(room.roomCents(), left);
            if (take > 0) {
                covers.addReturned(room.id(), take);
                left -= take;
            }
        }
        if (left > 0) {
            // The ledger and the covers disagree: refuse, so the event stays visible instead of drifting the fund.
            throw new IllegalStateException("voided loan " + loanId + " returned " + amountCents
                    + " cents of cover but its approved covers only paid " + (amountCents - left));
        }
        FundState fund = funds.lock();
        LocalDate today = clock.today();
        funds.save(fund.returnCover(amountCents));
        returns.insert(new ReturnRow(returnRef, LOAN_VOIDED, loanId, amountCents, null), LOAN_VOIDED, today);
        events.publishEvent(new CoverReturned(Ids.newId(), returnRef, LOAN_VOIDED, loanId, amountCents, null, LOAN_VOIDED, today));
        audit(loanId, returnRef, LOAN_VOIDED, loanId, amountCents, LOAN_VOIDED);
        log.warn("Returned {} cents of cover on voided loan {} to the fund", amountCents, loanId);
    }

    private CoverReturn replay(ReturnRow earlier, long amountCents) {
        if (earlier.amountCents() != amountCents) {
            throw new DomainException(CommonErrors.IDEMPOTENCY_KEY_REUSED,
                    "The return " + earlier.returnRef() + " was recorded for another amount");
        }
        return new CoverReturn(earlier.returnRef(), earlier.loanId(), earlier.amountCents(), earlier.journalEntryId(), true);
    }

    private static UUID coverIdOf(String approvalRef) {
        if (approvalRef == null || !approvalRef.startsWith(APPROVAL_PREFIX)) {
            throw new DomainException(ReserveErrors.COVER_RETURN_INVALID, "That is not a cover approval reference");
        }
        try {
            return UUID.fromString(approvalRef.substring(APPROVAL_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new DomainException(ReserveErrors.COVER_RETURN_INVALID, "That is not a cover approval reference");
        }
    }

    private void audit(UUID subject, String returnRef, String kind, UUID loanId, long amountCents, String reason) {
        governance.audit(new AuditCommand("RESERVE_COVER_RETURNED", subject, "SYSTEM", "reserve", "{\"returnRef\":\""
                + returnRef + "\",\"kind\":\"" + kind + "\",\"loanId\":\"" + loanId + "\",\"amountCents\":" + amountCents
                + ",\"reason\":\"" + reason + "\"}"));
    }
}
