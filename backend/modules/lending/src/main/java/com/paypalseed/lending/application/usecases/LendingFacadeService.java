package com.paypalseed.lending.application.usecases;

import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.RegisterDisbursedLoan;
import com.paypalseed.lending.api.dto.LendingDtos.ReschedulePreview;
import com.paypalseed.lending.api.dto.LendingDtos.RescheduleRequest;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class LendingFacadeService implements LendingFacade {

    private final LoanRegistration registration;
    private final RescheduleService reschedules;
    private final LoanQueries queries;
    private final LoanRepository loans;

    LendingFacadeService(LoanRegistration registration, RescheduleService reschedules, LoanQueries queries,
                         LoanRepository loans) {
        this.registration = registration;
        this.reschedules = reschedules;
        this.queries = queries;
        this.loans = loans;
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
        return reschedules.apply(request, approvalRef);
    }
}
