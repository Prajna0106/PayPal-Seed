package com.paypalseed.lending.api;

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
import java.time.LocalDate;
import java.util.List;
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

    /**
     * Applies an approved option exactly as approved. Idempotent on {@code approvalRef}: a replay returns the
     * version it created. Refuses with {@code reschedule-preview-changed} when the recomputed proposal no
     * longer equals {@code expectedProposed} (a payment or another change landed after the approval).
     */
    ScheduleView applyReschedule(RescheduleRequest request, String approvalRef, List<Slot> expectedProposed);

    /** Opens or clears the open-case flag; joins the caller's transaction. Suppresses retries and grace nudges. */
    void setHardshipOpen(UUID loanId, String caseRef, boolean open);

    /** Holds the InstalmentMissed fact for one instalment until {@code until}; joins the caller's transaction. */
    void placeHold(UUID loanId, int seq, LocalDate until, String caseRef, String cause);

    /** Releases every hold of a case; idempotent; joins the caller's transaction. */
    void releaseHold(String caseRef, String reason);

    Optional<RiskFacts> riskFacts(UUID loanId, LocalDate asOf);

    /** Active loans whose oldest owed instalment is due in {@code [asOf - graceDays, asOf + leadDays]}. */
    List<UUID> hardshipScanCandidates(LocalDate asOf, int leadDays, int graceDays, int shardGroup, int shardCount,
                                      UUID after, int limit);

    /** What the member can borrow now, by lending's own rules. Reads savings, income and the credit record; writes nothing. */
    EligibilityView eligibilityOf(UUID memberId);

    /** The member's loans, any status, newest first. Scoped to the member by construction; no risk facts. */
    List<MemberLoanView> loansOfMember(UUID memberId);

    /** The member's applications, newest first. No officer ids and no sizing reasons. */
    List<MemberApplicationView> applicationsOfMember(UUID memberId);

    /**
     * Applies money the reserve already moved to this loan in the ledger, as a repayment from {@code RESERVE}.
     * Idempotent on {@code approvalRef}; takes at most what the loan still owes; posts nothing to the ledger.
     * Joins the caller's transaction when there is one.
     */
    CoverResult applyReserveCover(ReserveCover cover);
}
