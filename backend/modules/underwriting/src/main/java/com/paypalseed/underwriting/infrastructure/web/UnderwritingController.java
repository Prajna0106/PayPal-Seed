package com.paypalseed.underwriting.infrastructure.web;

import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.ConsentStatusView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService.RunFairCheckCommand;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment.OfficerDecisionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for Feature 3: The Fair Loan Check (Blueprint §6).
 * Exposes member consent management, assessment execution, number dispute flagging,
 * and loan officer review decisions.
 */
@RestController
@RequestMapping("/api/v1")
public class UnderwritingController {

    public record RunAssessmentRequest(
        UUID memberId,
        UUID applicationId,
        @NotBlank String purpose,
        @Positive long requestedAmountCents,
        @Min(3) @Max(18) int requestedTermMonths,
        List<IncomeMonthDto> monthlyIncomes,
        long statedMonthlyIncomeCents,
        long statedMonthlyExpensesCents,
        Integer completedLoansOnTime,
        Boolean hasActiveLoan
    ) {
    }

    public record DisputeAssessmentRequest(
        @NotBlank String note
    ) {
    }

    public record OfficerDecisionRequest(
        UUID officerId,
        @NotNull OfficerDecisionType decision,
        Long adjustedLoanCents,
        Integer adjustedTermMonths,
        @NotBlank String reason
    ) {
    }

    private final FairLoanCheckService fairLoanCheckService;

    public UnderwritingController(FairLoanCheckService fairLoanCheckService) {
        this.fairLoanCheckService = fairLoanCheckService;
    }

    @GetMapping("/underwriting/consent")
    public ResponseEntity<ConsentStatusView> getConsentStatus(
        @RequestHeader(value = "X-Member-Id", required = false) UUID headerMemberId,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId
    ) {
        UUID memberId = resolveMemberId(headerMemberId, queryMemberId);
        return ResponseEntity.ok(fairLoanCheckService.getConsentStatus(memberId));
    }

    @PostMapping("/underwriting/consent")
    public ResponseEntity<ConsentStatusView> grantConsent(
        @RequestHeader(value = "X-Member-Id", required = false) UUID headerMemberId,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId
    ) {
        UUID memberId = resolveMemberId(headerMemberId, queryMemberId);
        return ResponseEntity.ok(fairLoanCheckService.grantConsent(memberId));
    }

    @DeleteMapping("/underwriting/consent")
    public ResponseEntity<ConsentStatusView> revokeConsent(
        @RequestHeader(value = "X-Member-Id", required = false) UUID headerMemberId,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId
    ) {
        UUID memberId = resolveMemberId(headerMemberId, queryMemberId);
        return ResponseEntity.ok(fairLoanCheckService.revokeConsent(memberId));
    }

    @PostMapping("/underwriting/assessments")
    public ResponseEntity<FairCheckCardView> runFairLoanCheck(
        @RequestHeader(value = "X-Member-Id", required = false) UUID headerMemberId,
        @Valid @RequestBody RunAssessmentRequest request
    ) {
        UUID memberId = resolveMemberId(headerMemberId, request.memberId());
        FairCheckCardView card = fairLoanCheckService.runFairLoanCheck(
            memberId,
            new RunFairCheckCommand(
                request.applicationId(),
                request.purpose(),
                request.requestedAmountCents(),
                request.requestedTermMonths(),
                request.monthlyIncomes(),
                request.statedMonthlyIncomeCents(),
                request.statedMonthlyExpensesCents(),
                request.completedLoansOnTime(),
                request.hasActiveLoan()
            )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(card);
    }

    @GetMapping("/underwriting/assessments")
    public ResponseEntity<List<FairCheckCardView>> listMemberAssessments(
        @RequestHeader(value = "X-Member-Id", required = false) UUID headerMemberId,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId
    ) {
        UUID memberId = resolveMemberId(headerMemberId, queryMemberId);
        return ResponseEntity.ok(fairLoanCheckService.listMemberAssessments(memberId));
    }

    @GetMapping("/underwriting/assessments/{assessmentId}")
    public ResponseEntity<FairCheckCardView> getAssessment(
        @PathVariable("assessmentId") UUID assessmentId
    ) {
        return ResponseEntity.ok(fairLoanCheckService.getAssessment(assessmentId));
    }

    @PostMapping("/underwriting/assessments/{assessmentId}/dispute")
    public ResponseEntity<FairCheckCardView> disputeAssessment(
        @PathVariable("assessmentId") UUID assessmentId,
        @RequestHeader(value = "X-Member-Id", required = false) UUID headerMemberId,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId,
        @Valid @RequestBody DisputeAssessmentRequest request
    ) {
        UUID memberId = resolveMemberId(headerMemberId, queryMemberId);
        return ResponseEntity.ok(fairLoanCheckService.disputeAssessment(assessmentId, memberId, request.note()));
    }

    @GetMapping("/desk/underwriting/assessments")
    public ResponseEntity<List<FairCheckCardView>> listDeskAssessments() {
        return ResponseEntity.ok(fairLoanCheckService.listAllAssessments());
    }

    @PostMapping("/desk/underwriting/assessments/{assessmentId}/decision")
    public ResponseEntity<FairCheckCardView> recordOfficerDecision(
        @PathVariable("assessmentId") UUID assessmentId,
        @RequestHeader(value = "X-Staff-Id", required = false) UUID headerOfficerId,
        @Valid @RequestBody OfficerDecisionRequest request
    ) {
        UUID officerId = headerOfficerId != null ? headerOfficerId : request.officerId();
        if (officerId == null) {
            throw new DomainException(CommonErrors.FORBIDDEN, "Loan officer identity (X-Staff-Id or officerId) is required");
        }
        return ResponseEntity.ok(fairLoanCheckService.recordOfficerDecision(
            assessmentId,
            officerId,
            request.decision(),
            request.adjustedLoanCents(),
            request.adjustedTermMonths(),
            request.reason()
        ));
    }

    private static UUID resolveMemberId(UUID headerMemberId, UUID fallbackMemberId) {
        if (headerMemberId != null) {
            return headerMemberId;
        }
        if (fallbackMemberId != null) {
            return fallbackMemberId;
        }
        throw new DomainException(CommonErrors.FORBIDDEN, "Member identity (X-Member-Id) is required");
    }
}
