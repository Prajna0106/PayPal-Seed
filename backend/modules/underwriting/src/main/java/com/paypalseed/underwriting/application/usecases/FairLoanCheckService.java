package com.paypalseed.underwriting.application.usecases;

import com.paypalseed.engines.assessment.AssessmentEngine;
import com.paypalseed.engines.assessment.AssessmentEngine.AssessmentInput;
import com.paypalseed.engines.assessment.AssessmentEngine.FairCheckResult;
import com.paypalseed.engines.assessment.AssessmentEngine.MonthlyIncomePoint;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.ConsentStatusView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment.OfficerDecisionType;
import com.paypalseed.underwriting.domain.model.UnderwritingErrors;
import com.paypalseed.underwriting.infrastructure.persistence.UnderwritingRepository;
import com.paypalseed.underwriting.infrastructure.persistence.UnderwritingRepository.ConsentRecord;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Application service for Feature 3: The Fair Loan Check (Blueprint §6).
 * Enforces explicit member consent, reads savings consistency via {@link SavingsFacade},
 * computes deterministic income/volatility metrics via {@link AssessmentEngine},
 * and persists auditable explanation cards and human officer decisions.
 */
@Service
public class FairLoanCheckService implements UnderwritingFacade {

    public record RunFairCheckCommand(
        UUID applicationId,
        String purpose,
        long requestedAmountCents,
        int requestedTermMonths,
        List<IncomeMonthDto> monthlyIncomes,
        long statedMonthlyIncomeCents,
        long statedMonthlyExpensesCents,
        Integer completedLoansOnTime,
        Boolean hasActiveLoan
    ) {
        public RunFairCheckCommand {
            monthlyIncomes = monthlyIncomes != null ? List.copyOf(monthlyIncomes) : List.of();
        }
    }

    private final UnderwritingRepository repository;
    private final SavingsFacade savingsFacade;

    public FairLoanCheckService(UnderwritingRepository repository, SavingsFacade savingsFacade) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.savingsFacade = Objects.requireNonNull(savingsFacade, "savingsFacade is required");
    }

    @Override
    public ConsentStatusView grantConsent(UUID memberId) {
        Objects.requireNonNull(memberId, "memberId is required");
        ConsentRecord saved = repository.saveConsent(memberId, true, Instant.now());
        return toConsentView(saved);
    }

    @Override
    public ConsentStatusView revokeConsent(UUID memberId) {
        Objects.requireNonNull(memberId, "memberId is required");
        ConsentRecord saved = repository.saveConsent(memberId, false, Instant.now());
        return toConsentView(saved);
    }

    @Override
    public ConsentStatusView getConsentStatus(UUID memberId) {
        Objects.requireNonNull(memberId, "memberId is required");
        return repository.findConsent(memberId)
            .map(FairLoanCheckService::toConsentView)
            .orElseGet(() -> new ConsentStatusView(
                memberId,
                false,
                null,
                AssessmentEngine.USED_DATA_DISCLOSURE,
                AssessmentEngine.NEVER_USED_DATA_DISCLOSURE
            ));
    }

    public FairCheckCardView runFairLoanCheck(UUID memberId, RunFairCheckCommand command) {
        Objects.requireNonNull(memberId, "memberId is required");
        Objects.requireNonNull(command, "command is required");

        boolean consented = repository.findConsent(memberId)
            .map(ConsentRecord::consented)
            .orElse(false);

        if (!consented) {
            throw new DomainException(
                UnderwritingErrors.CONSENT_REQUIRED,
                "Member must grant explicit consent before Seed can read savings and PayPal/Venmo income for the Fair Loan Check"
            );
        }

        MemberSavingsSummary savings = savingsFacade.getMemberSavingsSummary(memberId.toString());
        List<MonthlyIncomePoint> incomePoints = command.monthlyIncomes().stream()
            .map(m -> new MonthlyIncomePoint(m.yearMonth(), m.amountCents()))
            .toList();

        AssessmentInput input = new AssessmentInput(
            incomePoints,
            command.statedMonthlyIncomeCents(),
            command.statedMonthlyExpensesCents(),
            savings.monthlySavingCents(),
            savings.currentStreakMonths(),
            Math.max(savings.currentStreakMonths(), 4),
            savings.missedDepositsInQuarter(),
            command.completedLoansOnTime() != null ? command.completedLoansOnTime() : 0,
            command.hasActiveLoan() != null && command.hasActiveLoan(),
            command.requestedAmountCents(),
            command.requestedTermMonths(),
            command.purpose()
        );

        FairCheckResult result = AssessmentEngine.assess(input);
        FairLoanCheckAssessment assessment = FairLoanCheckAssessment.create(
            memberId,
            command.applicationId(),
            command.purpose(),
            result,
            Instant.now()
        );

        repository.saveAssessment(assessment);
        return toCardView(assessment);
    }

    public FairCheckCardView disputeAssessment(UUID assessmentId, UUID memberId, String note) {
        FairLoanCheckAssessment assessment = findOrThrow(assessmentId);
        if (!assessment.getMemberId().equals(memberId)) {
            throw new DomainException(UnderwritingErrors.ASSESSMENT_NOT_FOUND, "Assessment not found");
        }
        assessment.flagMemberDispute(note, Instant.now());
        repository.saveAssessment(assessment);
        return toCardView(assessment);
    }

    public FairCheckCardView recordOfficerDecision(
        UUID assessmentId,
        UUID officerId,
        OfficerDecisionType decision,
        Long adjustedLoanCents,
        Integer adjustedTermMonths,
        String reason
    ) {
        FairLoanCheckAssessment assessment = findOrThrow(assessmentId);
        assessment.recordOfficerDecision(
            officerId,
            decision,
            adjustedLoanCents,
            adjustedTermMonths,
            reason,
            Instant.now()
        );
        repository.saveAssessment(assessment);
        return toCardView(assessment);
    }

    @Override
    public Optional<FairCheckCardView> findAssessment(UUID assessmentId) {
        return repository.findAssessmentById(assessmentId).map(FairLoanCheckService::toCardView);
    }

    public FairCheckCardView getAssessment(UUID assessmentId) {
        return toCardView(findOrThrow(assessmentId));
    }

    public List<FairCheckCardView> listMemberAssessments(UUID memberId) {
        return repository.findAssessmentsByMemberId(memberId).stream()
            .map(FairLoanCheckService::toCardView)
            .toList();
    }

    public List<FairCheckCardView> listAllAssessments() {
        return repository.findAllAssessments().stream()
            .map(FairLoanCheckService::toCardView)
            .toList();
    }

    private FairLoanCheckAssessment findOrThrow(UUID id) {
        return repository.findAssessmentById(id)
            .orElseThrow(() -> new DomainException(UnderwritingErrors.ASSESSMENT_NOT_FOUND, "Assessment not found: " + id));
    }

    private static ConsentStatusView toConsentView(ConsentRecord rec) {
        return new ConsentStatusView(
            rec.memberId(),
            rec.consented(),
            rec.updatedAt(),
            AssessmentEngine.USED_DATA_DISCLOSURE,
            AssessmentEngine.NEVER_USED_DATA_DISCLOSURE
        );
    }

    public static FairCheckCardView toCardView(FairLoanCheckAssessment a) {
        FairCheckResult r = a.getResult();
        return new FairCheckCardView(
            a.getId(),
            a.getMemberId(),
            a.getApplicationId(),
            a.getPurpose(),
            r.recommended(),
            r.unlockedTier().name(),
            r.usedFallbackStatedIncome(),
            r.averageMonthlyIncomeCents(),
            r.lowestMonthlyIncomeCents(),
            r.lowestIncomeMonth(),
            r.coefficientOfVariation(),
            r.highIncomeVolatility(),
            r.provenMonthlySavingCents(),
            r.monthsSaved(),
            r.targetMonthsWindow(),
            r.instalmentCapCents(),
            r.requestedAmountCents(),
            r.requestedTermMonths(),
            r.suggestedLoanCents(),
            r.suggestedTermMonths(),
            r.suggestedMonthlyInstalmentCents(),
            r.inputsHash(),
            a.getModelVersion(),
            r.plainLanguageReasons(),
            r.officerQuestions(),
            r.usedData(),
            r.neverUsedData(),
            a.isMemberDisputed(),
            a.getMemberDisputeNote(),
            a.getOfficerDecision().name(),
            a.getOfficerId(),
            a.getDecidedLoanCents(),
            a.getDecidedTermMonths(),
            a.getOfficerReason(),
            a.getCreatedAt(),
            a.getDecidedAt()
        );
    }
}
