package com.paypalseed.hardship.application.usecases;

import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.CaseStatus;
import com.paypalseed.hardship.infrastructure.persistence.CaseRepository;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.InstalmentLine;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How an open case follows what happens to its loan outside it (02-design §2): the member catching up alone
 * ends it as CANCELLED_PAID, and a schedule change made by another route ends it as SUPERSEDED.
 */
@Service
public class CaseReactions {

    /** States in which the member's own payment makes the case unnecessary. */
    private static final Set<CaseStatus> PAYABLE = EnumSet.of(CaseStatus.DETECTED, CaseStatus.OUTREACH_SENT, CaseStatus.OFFER_VIEWED,
            CaseStatus.OPTION_CHOSEN, CaseStatus.PENDING_APPROVAL);

    private final CaseRepository cases;
    private final CaseLifecycle lifecycle;
    private final LendingFacade lending;

    public CaseReactions(CaseRepository cases, CaseLifecycle lifecycle, LendingFacade lending) {
        this.cases = cases;
        this.lifecycle = lifecycle;
        this.lending = lending;
    }

    /** A payment landed: if the target instalment is now paid, the case ends without anyone deciding anything. */
    @Transactional
    public void onPayment(UUID loanId, boolean loanRepaid) {
        Optional<CaseRow> open = cases.openForLoan(loanId).flatMap(c -> cases.lock(c.id()));
        if (open.isEmpty() || !PAYABLE.contains(open.get().status())) {
            return;
        }
        CaseRow c = open.get();
        long remaining = loanRepaid ? 0 : lending.currentSchedule(loanId).instalments().stream()
                .filter(i -> i.seq() == c.targetSeq())
                .mapToLong(InstalmentLine::remainingCents)
                .findFirst().orElse(0);
        if (remaining == 0) {
            lifecycle.close(c, CaseStatus.CANCELLED_PAID, loanRepaid ? "LOAN_REPAID" : "MEMBER_PAID");
        }
    }

    /** Our own apply carries {@code approval:hardship:<caseId>:...}; anything else replaces the plan the offer was built on. */
    @Transactional
    public void onScheduleChanged(UUID loanId, String causedBy) {
        Optional<CaseRow> open = cases.openForLoan(loanId).flatMap(c -> cases.lock(c.id()));
        if (open.isEmpty()) {
            return;
        }
        CaseRow c = open.get();
        if (causedBy != null && causedBy.startsWith("approval:hardship:" + c.id())) {
            return;
        }
        if (c.status().canMoveTo(CaseStatus.SUPERSEDED) && c.status() != CaseStatus.APPROVED) {
            lifecycle.close(c, CaseStatus.SUPERSEDED, "SCHEDULE_CHANGED");
        }
    }
}
