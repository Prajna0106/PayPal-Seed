package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.infrastructure.persistence.HardshipViewRepository;
import com.paypalseed.hardship.api.events.HardshipEvents.HardshipApplied;
import com.paypalseed.hardship.api.events.HardshipEvents.HardshipCaseClosed;
import com.paypalseed.hardship.api.events.HardshipEvents.HardshipCaseOpened;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hardship cases for the desk's counts and the loan grid's hardship flag. A case closed is closed whatever order its
 * events arrive in. Member references are copied as opaque ids; no case detail beyond the level and the origin is kept.
 */
@Service
public class HardshipProjector {

    private final HardshipViewRepository cases;
    private final LoanProjector loans;

    HardshipProjector(HardshipViewRepository cases, LoanProjector loans) {
        this.cases = cases;
        this.loans = loans;
    }

    @Transactional
    public void on(HardshipCaseOpened e) {
        cases.opened(e.caseId(), e.loanId(), e.memberId(), e.level(), e.origin(), e.businessDate());
        loans.refreshHardshipFlag(e.loanId(), e.memberId());
    }

    @Transactional
    public void on(HardshipApplied e) {
        cases.applied(e.caseId(), e.loanId(), e.memberId());
        loans.refreshHardshipFlag(e.loanId(), e.memberId());
    }

    @Transactional
    public void on(HardshipCaseClosed e) {
        cases.closed(e.caseId(), e.loanId(), e.memberId(), e.businessDate(), e.closedReason());
        loans.refreshHardshipFlag(e.loanId(), e.memberId());
    }
}
