package com.paypalseed.underwriting.infrastructure.web;

import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.ConsentStatusView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService.RunFairCheckCommand;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment.OfficerDecisionType;
import com.paypalseed.underwriting.domain.model.UnderwritingErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for Feature 3: The Fair Loan Check (Blueprint §6).
 * Exposes member consent management, assessment execution, number dispute flagging,
 * and loan officer review decisions. Member and officer ids come only from the {@link Actor}.
 * Income history comes from the member's consented record, never the request: a member can only state an income,
 * which the engine treats as an unverified fallback.
 */
@RestController
@RequestMapping("/api/v1")
public class UnderwritingController {

    public record RunAssessmentRequest(
        @NotBlank String purpose,
        @Positive long requestedAmountCents,
        @Min(3) @Max(18) int requestedTermMonths,
        @PositiveOrZero long statedMonthlyIncomeCents,
        @PositiveOrZero long statedMonthlyExpensesCents
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
    private final UnderwritingFacade underwriting;

    public UnderwritingController(FairLoanCheckService fairLoanCheckService, UnderwritingFacade underwriting) {
        this.fairLoanCheckService = fairLoanCheckService;
        this.underwriting = underwriting;
    }

    @GetMapping("/underwriting/consent")
    public ResponseEntity<ConsentStatusView> getConsentStatus(Actor actor) {
        return ResponseEntity.ok(fairLoanCheckService.getConsentStatus(actor.requireMember()));
    }

    @PostMapping("/underwriting/consent")
    public ResponseEntity<ConsentStatusView> grantConsent(Actor actor) {
        return ResponseEntity.ok(fairLoanCheckService.grantConsent(actor.requireMember()));
    }

    @DeleteMapping("/underwriting/consent")
    public ResponseEntity<ConsentStatusView> revokeConsent(Actor actor) {
        return ResponseEntity.ok(fairLoanCheckService.revokeConsent(actor.requireMember()));
    }

    @PostMapping("/underwriting/assessments")
    public ResponseEntity<FairCheckCardView> runFairLoanCheck(
        Actor actor,
        @Valid @RequestBody RunAssessmentRequest request
    ) {
        UUID memberId = actor.requireMember();
        FairCheckCardView card = fairLoanCheckService.runFairLoanCheck(
            memberId,
            new RunFairCheckCommand(
                null,
                request.purpose(),
                request.requestedAmountCents(),
                request.requestedTermMonths(),
                underwriting.consentedMonthlyIncomes(memberId),
                request.statedMonthlyIncomeCents(),
                request.statedMonthlyExpensesCents(),
                null,
                null
            )
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(card);
    }

    @GetMapping("/underwriting/assessments")
    public ResponseEntity<List<FairCheckCardView>> listMemberAssessments(Actor actor) {
        return ResponseEntity.ok(fairLoanCheckService.listMemberAssessments(actor.requireMember()));
    }

    @GetMapping("/underwriting/assessments/{assessmentId}")
    public ResponseEntity<FairCheckCardView> getAssessment(
        Actor actor,
        @PathVariable("assessmentId") UUID assessmentId
    ) {
        if (actor.type() != Actor.Type.MEMBER) {
            actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
            return ResponseEntity.ok(fairLoanCheckService.getAssessment(assessmentId));
        }
        FairCheckCardView card = fairLoanCheckService.getAssessment(assessmentId);
        if (!card.memberId().equals(actor.requireMember())) {
            throw new DomainException(UnderwritingErrors.ASSESSMENT_NOT_FOUND, "Assessment not found");
        }
        return ResponseEntity.ok(card);
    }

    @PostMapping("/underwriting/assessments/{assessmentId}/dispute")
    public ResponseEntity<FairCheckCardView> disputeAssessment(
        Actor actor,
        @PathVariable("assessmentId") UUID assessmentId,
        @Valid @RequestBody DisputeAssessmentRequest request
    ) {
        return ResponseEntity.ok(fairLoanCheckService.disputeAssessment(assessmentId, actor.requireMember(), request.note()));
    }

    @GetMapping("/desk/underwriting/assessments")
    public ResponseEntity<List<FairCheckCardView>> listDeskAssessments(Actor actor) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
        return ResponseEntity.ok(fairLoanCheckService.listAllAssessments());
    }

    @PostMapping("/desk/underwriting/assessments/{assessmentId}/decision")
    public ResponseEntity<FairCheckCardView> recordOfficerDecision(
        Actor actor,
        @PathVariable("assessmentId") UUID assessmentId,
        @Valid @RequestBody OfficerDecisionRequest request
    ) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER);
        return ResponseEntity.ok(fairLoanCheckService.recordOfficerDecision(
            assessmentId,
            actor.id(),
            request.decision(),
            request.adjustedLoanCents(),
            request.adjustedTermMonths(),
            request.reason()
        ));
    }
}
