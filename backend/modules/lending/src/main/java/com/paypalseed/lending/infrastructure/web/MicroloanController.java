package com.paypalseed.lending.infrastructure.web;

import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for Feature 2: Savings-linked microloans (Blueprint §5).
 * Exposes member eligibility & application routes as well as desk (maker-checker) review & disbursement routes.
 */
@RestController
@RequestMapping("/api/v1")
public class MicroloanController {

    public record SubmitLoanApplicationRequest(
        UUID memberId,
        @NotBlank String purpose,
        @Positive long requestedAmountCents,
        @Min(3) @Max(18) int termMonths,
        @Min(1) @Max(28) Integer preferredDueDay,
        WalletType payoutWallet,
        boolean fairCheckConsented,
        Long verifiedMonthlyIncomeCents
    ) {
    }

    public record OfficerApprovalRequest(
        UUID officerId
    ) {
    }

    public record OfficerRejectionRequest(
        UUID officerId,
        String reason
    ) {
    }

    private final MicroloanApplicationService applicationService;

    public MicroloanController(MicroloanApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping("/loans/eligibility")
    public ResponseEntity<EligibilityView> getEligibility(
        @RequestHeader(value = "X-Member-Id", required = false) UUID headerMemberId,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId,
        @RequestParam(value = "verifiedMonthlyIncomeCents", required = false) Long verifiedMonthlyIncomeCents
    ) {
        UUID memberId = resolveMemberId(headerMemberId, queryMemberId);
        OptionalLong income = verifiedMonthlyIncomeCents != null
            ? OptionalLong.of(verifiedMonthlyIncomeCents)
            : OptionalLong.empty();
        return ResponseEntity.ok(applicationService.checkEligibility(memberId, income));
    }

    @PostMapping("/loans/applications")
    public ResponseEntity<LoanApplicationView> submitApplication(
        @RequestHeader(value = "X-Member-Id", required = false) UUID headerMemberId,
        @Valid @RequestBody SubmitLoanApplicationRequest request
    ) {
        UUID memberId = resolveMemberId(headerMemberId, request.memberId());
        OptionalLong income = request.verifiedMonthlyIncomeCents() != null
            ? OptionalLong.of(request.verifiedMonthlyIncomeCents())
            : OptionalLong.empty();

        LoanApplicationView created = applicationService.submitApplication(
            memberId,
            new SubmitApplicationCommand(
                request.purpose(),
                request.requestedAmountCents(),
                request.termMonths(),
                request.preferredDueDay(),
                request.payoutWallet(),
                request.fairCheckConsented(),
                income
            )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/loans/applications")
    public ResponseEntity<List<LoanApplicationView>> listMemberApplications(
        @RequestHeader(value = "X-Member-Id", required = false) UUID headerMemberId,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId
    ) {
        UUID memberId = resolveMemberId(headerMemberId, queryMemberId);
        return ResponseEntity.ok(applicationService.listMemberApplications(memberId));
    }

    @GetMapping("/loans/applications/{applicationId}")
    public ResponseEntity<LoanApplicationView> getApplication(
        @PathVariable("applicationId") UUID applicationId,
        @RequestHeader(value = "X-Member-Id", required = false) UUID headerMemberId
    ) {
        if (headerMemberId != null) {
            return ResponseEntity.ok(applicationService.getMemberApplication(applicationId, headerMemberId));
        }
        return ResponseEntity.ok(applicationService.getApplication(applicationId));
    }

    @PostMapping("/loans/applications/{applicationId}/cooling-off-cancel")
    public ResponseEntity<LoanApplicationView> cancelDuringCoolingOff(
        @PathVariable("applicationId") UUID applicationId,
        @RequestHeader(value = "X-Member-Id", required = false) UUID headerMemberId,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId
    ) {
        UUID memberId = resolveMemberId(headerMemberId, queryMemberId);
        return ResponseEntity.ok(applicationService.cancelWithinCoolingOff(applicationId, memberId));
    }

    // --- Desk (Loan Officer) Maker-Checker Endpoints ---

    @GetMapping("/desk/loans/applications")
    public ResponseEntity<List<LoanApplicationView>> listDeskApplications() {
        return ResponseEntity.ok(applicationService.listAllApplications());
    }

    @PostMapping("/desk/loans/applications/{applicationId}/approve")
    public ResponseEntity<LoanApplicationView> approveApplication(
        @PathVariable("applicationId") UUID applicationId,
        @RequestHeader(value = "X-Staff-Id", required = false) UUID headerOfficerId,
        @RequestBody(required = false) OfficerApprovalRequest request
    ) {
        UUID officerId = resolveOfficerId(headerOfficerId, request != null ? request.officerId() : null);
        return ResponseEntity.ok(applicationService.approveApplication(applicationId, officerId));
    }

    @PostMapping("/desk/loans/applications/{applicationId}/reject")
    public ResponseEntity<LoanApplicationView> rejectApplication(
        @PathVariable("applicationId") UUID applicationId,
        @RequestHeader(value = "X-Staff-Id", required = false) UUID headerOfficerId,
        @RequestBody(required = false) OfficerRejectionRequest request
    ) {
        UUID officerId = resolveOfficerId(headerOfficerId, request != null ? request.officerId() : null);
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.ok(applicationService.rejectApplication(applicationId, officerId, reason));
    }

    @PostMapping("/desk/loans/applications/{applicationId}/disburse")
    public ResponseEntity<LoanApplicationView> disburseApplication(
        @PathVariable("applicationId") UUID applicationId
    ) {
        return ResponseEntity.ok(applicationService.disburseApplication(applicationId));
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

    private static UUID resolveOfficerId(UUID headerOfficerId, UUID bodyOfficerId) {
        if (headerOfficerId != null) {
            return headerOfficerId;
        }
        if (bodyOfficerId != null) {
            return bodyOfficerId;
        }
        throw new DomainException(CommonErrors.FORBIDDEN, "Loan officer identity (X-Staff-Id or officerId) is required");
    }
}
