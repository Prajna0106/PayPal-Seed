package com.paypalseed.underwriting.infrastructure.web;

import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.ConsentStatusView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService.RunFairCheckCommand;
import com.paypalseed.underwriting.domain.model.UnderwritingErrors;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for Feature 3: The Fair Loan Check (Blueprint §6).
 * Exposes member consent management, assessment execution, number dispute flagging,
 * and loan officer review decisions.
 *
 * <p>Who is calling comes from the verified {@link Actor} (ADR 0016), never from a header the caller wrote. A member
 * route works on the actor's own data; a {@code memberId} in the query or body is accepted only if it is the actor's
 * own and otherwise answers {@code 404}. Every {@code /desk} route needs an officer, a checker or an admin, and the
 * officer on a decision is the actor.
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
        Actor actor,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId
    ) {
        return ResponseEntity.ok(fairLoanCheckService.getConsentStatus(ownMemberId(actor, queryMemberId)));
    }

    @PostMapping("/underwriting/consent")
    public ResponseEntity<ConsentStatusView> grantConsent(
        Actor actor,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId
    ) {
        return ResponseEntity.ok(fairLoanCheckService.grantConsent(ownMemberId(actor, queryMemberId)));
    }

    @DeleteMapping("/underwriting/consent")
    public ResponseEntity<ConsentStatusView> revokeConsent(
        Actor actor,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId
    ) {
        return ResponseEntity.ok(fairLoanCheckService.revokeConsent(ownMemberId(actor, queryMemberId)));
    }

    @PostMapping("/underwriting/assessments")
    public ResponseEntity<FairCheckCardView> runFairLoanCheck(
        Actor actor,
        @Valid @RequestBody RunAssessmentRequest request
    ) {
        UUID memberId = ownMemberId(actor, request.memberId());
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
        Actor actor,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId
    ) {
        return ResponseEntity.ok(fairLoanCheckService.listMemberAssessments(ownMemberId(actor, queryMemberId)));
    }

    /** A member sees only their own assessment; an officer, checker or admin sees any. */
    @GetMapping("/underwriting/assessments/{assessmentId}")
    public ResponseEntity<FairCheckCardView> getAssessment(
        Actor actor,
        @PathVariable("assessmentId") UUID assessmentId
    ) {
        if (actor.type() == Actor.Type.MEMBER) {
            FairCheckCardView card = fairLoanCheckService.getAssessment(assessmentId);
            if (!actor.id().equals(card.memberId())) {
                throw new DomainException(UnderwritingErrors.ASSESSMENT_NOT_FOUND, "Assessment not found");
            }
            return ResponseEntity.ok(card);
        }
        staff(actor);
        return ResponseEntity.ok(fairLoanCheckService.getAssessment(assessmentId));
    }

    @PostMapping("/underwriting/assessments/{assessmentId}/dispute")
    public ResponseEntity<FairCheckCardView> disputeAssessment(
        Actor actor,
        @PathVariable("assessmentId") UUID assessmentId,
        @RequestParam(value = "memberId", required = false) UUID queryMemberId,
        @Valid @RequestBody DisputeAssessmentRequest request
    ) {
        UUID memberId = ownMemberId(actor, queryMemberId);
        return ResponseEntity.ok(fairLoanCheckService.disputeAssessment(assessmentId, memberId, request.note()));
    }

    @GetMapping("/desk/underwriting/assessments")
    public ResponseEntity<List<FairCheckCardView>> listDeskAssessments(Actor actor) {
        staff(actor);
        return ResponseEntity.ok(fairLoanCheckService.listAllAssessments());
    }

    @PostMapping("/desk/underwriting/assessments/{assessmentId}/decision")
    public ResponseEntity<FairCheckCardView> recordOfficerDecision(
        Actor actor,
        @PathVariable("assessmentId") UUID assessmentId,
        @Valid @RequestBody OfficerDecisionRequest request
    ) {
        UUID officerId = staff(actor).id();
        return ResponseEntity.ok(fairLoanCheckService.recordOfficerDecision(
            assessmentId,
            officerId,
            request.decision(),
            request.adjustedLoanCents(),
            request.adjustedTermMonths(),
            request.reason()
        ));
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
