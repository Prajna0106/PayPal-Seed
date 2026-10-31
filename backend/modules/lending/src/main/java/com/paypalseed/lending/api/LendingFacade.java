package com.paypalseed.lending.api;

import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.RegisterDisbursedLoan;
import com.paypalseed.lending.api.dto.LendingDtos.ReschedulePreview;
import com.paypalseed.lending.api.dto.LendingDtos.RescheduleRequest;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import java.util.Optional;
import java.util.UUID;

/** What other modules (F2 loans, F5 hardship, agent tools) may ask of lending. */
public interface LendingFacade {

    /** Creates the loan and schedule version 1 and books the disbursement. Idempotent on the disbursement ref. */
    LoanSummary registerDisbursedLoan(RegisterDisbursedLoan command);

    Optional<LoanSummary> loan(UUID loanId);

    ScheduleView currentSchedule(UUID loanId);

    /** What a hardship option would do, without writing anything. */
    ReschedulePreview previewReschedule(RescheduleRequest request);

    /** Applies an approved hardship option as a new schedule version. */
    ScheduleView applyReschedule(RescheduleRequest request, String approvalRef);
}
