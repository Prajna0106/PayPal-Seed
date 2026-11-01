package com.paypalseed.lending.infrastructure.web;

import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for Feature 2: Savings-linked microloans (Blueprint §5).
 * Exposes member eligibility & application routes as well as desk (maker-checker) review & disbursement routes.
 * Member and officer ids come only from the {@link Actor}. Income is never accepted from the caller: the service
 * resolves it from underwriting, because it sizes the loan.
 */
@RestController
@RequestMapping("/api/v1")
public class MicroloanController {

    public record SubmitLoanApplicationRequest(
        @NotBlank String purpose,
        @Positive long requestedAmountCents,
        @Min(3) @Max(18) int termMonths,
        @Min(1) @Max(28) Integer preferredDueDay,
        WalletType payoutWallet,
        boolean fairCheckConsented
    ) {
    }

    public record OfficerRejectionRequest(
        String reason
    ) {
    }

    private final MicroloanApplicationService applicationService;

    public MicroloanController(MicroloanApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping("/loans/eligibility")
    public ResponseEntity<EligibilityView> getEligibility(Actor actor) {
        return ResponseEntity.ok(applicationService.checkEligibility(actor.requireMember(), OptionalLong.empty()));
    }

    @PostMapping("/loans/applications")
    public ResponseEntity<LoanApplicationView> submitApplication(
        Actor actor,
        @Valid @RequestBody SubmitLoanApplicationRequest request
    ) {
        LoanApplicationView created = applicationService.submitApplication(
            actor.requireMember(),
            new SubmitApplicationCommand(
                request.purpose(),
                request.requestedAmountCents(),
                request.termMonths(),
                request.preferredDueDay(),
                request.payoutWallet(),
                request.fairCheckConsented(),
                OptionalLong.empty()
            )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/loans/applications")
    public ResponseEntity<List<LoanApplicationView>> listMemberApplications(Actor actor) {
        return ResponseEntity.ok(applicationService.listMemberApplications(actor.requireMember()));
    }

    @GetMapping("/loans/applications/{applicationId}")
    public ResponseEntity<LoanApplicationView> getApplication(
        Actor actor,
        @PathVariable("applicationId") UUID applicationId
    ) {
        if (actor.type() == Actor.Type.MEMBER) {
            return ResponseEntity.ok(applicationService.getMemberApplication(applicationId, actor.requireMember()));
        }
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
        return ResponseEntity.ok(applicationService.getApplication(applicationId));
    }

    @PostMapping("/loans/applications/{applicationId}/cooling-off-cancel")
    public ResponseEntity<LoanApplicationView> cancelDuringCoolingOff(
        Actor actor,
        @PathVariable("applicationId") UUID applicationId
    ) {
        return ResponseEntity.ok(applicationService.cancelWithinCoolingOff(applicationId, actor.requireMember()));
    }

    // --- Desk (Loan Officer) Maker-Checker Endpoints ---

    @GetMapping("/desk/loans/applications")
    public ResponseEntity<List<LoanApplicationView>> listDeskApplications(Actor actor) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
        return ResponseEntity.ok(applicationService.listAllApplications());
    }

    @PostMapping("/desk/loans/applications/{applicationId}/approve")
    public ResponseEntity<LoanApplicationView> approveApplication(
        Actor actor,
        @PathVariable("applicationId") UUID applicationId
    ) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER);
        return ResponseEntity.ok(applicationService.approveApplication(applicationId, actor.id()));
    }

    @PostMapping("/desk/loans/applications/{applicationId}/reject")
    public ResponseEntity<LoanApplicationView> rejectApplication(
        Actor actor,
        @PathVariable("applicationId") UUID applicationId,
        @RequestBody(required = false) OfficerRejectionRequest request
    ) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER);
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.ok(applicationService.rejectApplication(applicationId, actor.id(), reason));
    }

    @PostMapping("/desk/loans/applications/{applicationId}/disburse")
    public ResponseEntity<LoanApplicationView> disburseApplication(
        Actor actor,
        @PathVariable("applicationId") UUID applicationId
    ) {
        actor.requireAnyRole(Role.OFFICER, Role.ADMIN);
        return ResponseEntity.ok(applicationService.disburseApplication(applicationId));
    }
}
