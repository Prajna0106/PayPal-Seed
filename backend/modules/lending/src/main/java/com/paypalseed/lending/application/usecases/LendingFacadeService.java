package com.paypalseed.lending.application.usecases;

import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.CoverResult;
import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.MemberApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberLoanView;
import com.paypalseed.lending.api.dto.LendingDtos.RegisterDisbursedLoan;
import com.paypalseed.lending.api.dto.LendingDtos.ReschedulePreview;
import com.paypalseed.lending.api.dto.LendingDtos.RescheduleRequest;
import com.paypalseed.lending.api.dto.LendingDtos.ReserveCover;
import com.paypalseed.lending.api.dto.LendingDtos.RiskFacts;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.lending.api.dto.LendingDtos.Slot;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
class LendingFacadeService implements LendingFacade {

    private final LoanRegistration registration;
    private final RescheduleService reschedules;
    private final LoanQueries queries;
    private final LoanRepository loans;
    private final HardshipSupportService hardship;
    private final ApplyReserveCover reserveCover;
    /**
     * Looked up on first use: the application service takes this facade, so asking for it in the constructor would be
     * a cycle.
     */
    private final ObjectProvider<MemberViews> memberViews;

    LendingFacadeService(LoanRegistration registration, RescheduleService reschedules, LoanQueries queries,
                         LoanRepository loans, HardshipSupportService hardship, ApplyReserveCover reserveCover,
                         ObjectProvider<MemberViews> memberViews) {
        this.registration = registration;
        this.reschedules = reschedules;
        this.queries = queries;
        this.loans = loans;
        this.hardship = hardship;
        this.reserveCover = reserveCover;
        this.memberViews = memberViews;
    }

    @Override
    public LoanSummary registerDisbursedLoan(RegisterDisbursedLoan command) {
        UUID id = registration.register(command);
        return LoanQueries.summary(queries.loan(id));
    }

    @Override
    public Optional<LoanSummary> loan(UUID loanId) {
        return loans.find(loanId).map(LoanQueries::summary);
    }

    @Override
    public ScheduleView currentSchedule(UUID loanId) {
        return queries.schedule(queries.loan(loanId), Optional.empty());
    }

    @Override
    public ReschedulePreview previewReschedule(RescheduleRequest request) {
        return reschedules.preview(queries.loan(request.loanId()), request);
    }

    @Override
    public ScheduleView applyReschedule(RescheduleRequest request, String approvalRef) {
        return reschedules.apply(request, approvalRef, null);
    }

    @Override
    public ScheduleView applyReschedule(RescheduleRequest request, String approvalRef, List<Slot> expectedProposed) {
        return reschedules.apply(request, approvalRef, List.copyOf(expectedProposed));
    }

    @Override
    public void setHardshipOpen(UUID loanId, String caseRef, boolean open) {
        hardship.setHardshipOpen(loanId, caseRef, open);
    }

    @Override
    public void placeHold(UUID loanId, int seq, LocalDate until, String caseRef, String cause) {
        hardship.placeHold(loanId, seq, until, caseRef, cause);
    }

    @Override
    public void releaseHold(String caseRef, String reason) {
        hardship.releaseHold(caseRef, reason);
    }

    @Override
    public Optional<RiskFacts> riskFacts(UUID loanId, LocalDate asOf) {
        return hardship.riskFacts(loanId, asOf);
    }

    @Override
    public List<UUID> hardshipScanCandidates(LocalDate asOf, int leadDays, int graceDays, int shardGroup, int shardCount,
                                             UUID after, int limit) {
        return hardship.scanCandidates(asOf, leadDays, graceDays, shardGroup, shardCount, after, limit);
    }

    @Override
    public EligibilityView eligibilityOf(UUID memberId) {
        return memberViews.getObject().eligibility(memberId);
    }

    @Override
    public List<MemberLoanView> loansOfMember(UUID memberId) {
        return memberViews.getObject().loans(memberId);
    }

    @Override
    public List<MemberApplicationView> applicationsOfMember(UUID memberId) {
        return memberViews.getObject().applications(memberId);
    }

    @Override
    public CoverResult applyReserveCover(ReserveCover cover) {
        return reserveCover.apply(cover);
    }
}
