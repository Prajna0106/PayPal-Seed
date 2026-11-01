package com.paypalseed.lending.infrastructure.web;

import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for Feature 2: Savings-linked microloans (Blueprint §5).
 * Exposes member eligibility and application routes as well as desk (maker-checker) review and disbursement routes.
 *
 * <p>Who is calling comes from the verified {@link Actor} (ADR 0016), never from a header the caller wrote. A member
 * route works on the actor's own data; a {@code memberId} in the query or body is accepted only if it is the actor's
 * own and otherwise answers {@code 404}, like a resource that does not exist. Every {@code /desk} route needs an
 * officer, a checker or an admin, and the officer on a decision is the actor.
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

    public record OfficerRejectionRequest(
        String reason
    ) {
    }

    private final MicroloanApplicationService applicationService;

    public MicroloanController(MicroloanApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping("/loans/eligibility")
    public ResponseEntity<EligibilityView> getEligibility(
        Actor actor,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId,
        @RequestParam(value = "verifiedMonthlyIncomeCents", required = false) Long verifiedMonthlyIncomeCents
    ) {
        UUID memberId = ownMemberId(actor, queryMemberId);
        OptionalLong income = verifiedMonthlyIncomeCents != null
            ? OptionalLong.of(verifiedMonthlyIncomeCents)
            : OptionalLong.empty();
        return ResponseEntity.ok(applicationService.checkEligibility(memberId, income));
    }

    @PostMapping("/loans/applications")
    public ResponseEntity<LoanApplicationView> submitApplication(
        Actor actor,
        @Valid @RequestBody SubmitLoanApplicationRequest request
    ) {
        UUID memberId = ownMemberId(actor, request.memberId());
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
        Actor actor,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId
    ) {
        return ResponseEntity.ok(applicationService.listMemberApplications(ownMemberId(actor, queryMemberId)));
    }

    /** A member sees only their own application; an officer, checker or admin sees any. */
    @GetMapping("/loans/applications/{applicationId}")
    public ResponseEntity<LoanApplicationView> getApplication(
        Actor actor,
        @PathVariable("applicationId") UUID applicationId
    ) {
        if (actor.type() == Actor.Type.MEMBER) {
            return ResponseEntity.ok(applicationService.getMemberApplication(applicationId, actor.id()));
        }
        staff(actor);
        return ResponseEntity.ok(applicationService.getApplication(applicationId));
    }

    @PostMapping("/loans/applications/{applicationId}/cooling-off-cancel")
    public ResponseEntity<LoanApplicationView> cancelDuringCoolingOff(
        Actor actor,
        @PathVariable("applicationId") UUID applicationId,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId
    ) {
        UUID memberId = ownMemberId(actor, queryMemberId);
        return ResponseEntity.ok(applicationService.cancelWithinCoolingOff(applicationId, memberId));
    }

    // --- Desk (Loan Officer) Maker-Checker Endpoints ---

    @GetMapping("/desk/loans/applications")
    public ResponseEntity<List<LoanApplicationView>> listDeskApplications(Actor actor) {
        staff(actor);
        return ResponseEntity.ok(applicationService.listAllApplications());
    }

    @PostMapping("/desk/loans/applications/{applicationId}/approve")
    public ResponseEntity<LoanApplicationView> approveApplication(
        Actor actor,
        @PathVariable("applicationId") UUID applicationId
    ) {
        return ResponseEntity.ok(applicationService.approveApplication(applicationId, staff(actor).id()));
    }

    @PostMapping("/desk/loans/applications/{applicationId}/reject")
    public ResponseEntity<LoanApplicationView> rejectApplication(
        Actor actor,
        @PathVariable("applicationId") UUID applicationId,
        @RequestBody(required = false) OfficerRejectionRequest request
    ) {
        UUID officerId = staff(actor).id();
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.ok(applicationService.rejectApplication(applicationId, officerId, reason));
    }

    /**
     * Pays the loan out. The caller must be an officer, checker or admin; the response may still say
     * {@code DISBURSING}, and the application becomes {@code DISBURSED} when PayPal confirms the payout.
     */
    @PostMapping("/desk/loans/applications/{applicationId}/disburse")
    public ResponseEntity<LoanApplicationView> disburseApplication(
        Actor actor,
        @PathVariable("applicationId") UUID applicationId
    ) {
        return ResponseEntity.ok(applicationService.disburseApplication(applicationId, staff(actor).id()));
    }

    private static Actor staff(Actor actor) {
        return actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
    }

    /** The caller's own member id. A member id that names someone else is reported as not found. */
    private static UUID ownMemberId(Actor actor, UUID named) {
        UUID own = actor.requireMember();
        if (named != null && !named.equals(own)) {
            throw new DomainException(CommonErrors.NOT_FOUND, "Not found");
        }
        return own;
    }
}
