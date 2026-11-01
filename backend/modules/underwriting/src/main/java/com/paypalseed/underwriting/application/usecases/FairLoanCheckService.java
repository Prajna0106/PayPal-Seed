package com.paypalseed.underwriting.application.usecases;

import com.paypalseed.engines.assessment.AssessmentEngine;
import com.paypalseed.engines.assessment.AssessmentEngine.AssessmentInput;
import com.paypalseed.engines.assessment.AssessmentEngine.FairCheckResult;
import com.paypalseed.engines.assessment.AssessmentEngine.MonthlyIncomePoint;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.underwriting.api.FairCheckNarrator;
import com.paypalseed.underwriting.api.FairCheckNarrator.NarrationInput;
import com.paypalseed.underwriting.api.FairCheckNarrator.NarrationResult;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.ConsentStatusView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.AssessmentStatusView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.api.events.AssessmentChanged;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment.OfficerDecisionType;
import com.paypalseed.underwriting.domain.model.UnderwritingErrors;
import com.paypalseed.underwriting.infrastructure.persistence.UnderwritingRepository;
import com.paypalseed.underwriting.infrastructure.persistence.UnderwritingRepository.ConsentRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application service for Feature 3: The Fair Loan Check (Blueprint §6).
 * Enforces explicit member consent, reads savings consistency via {@link SavingsFacade},
 * computes deterministic income/volatility metrics via {@link AssessmentEngine},
 * optionally narrates the explanation card via {@link FairCheckNarrator},
 * and persists auditable explanation cards and human officer decisions.
 *
 * <p>Reading PayPal and calling the narrator happen outside any database transaction. Saving an assessment and
 * publishing {@link AssessmentChanged} happen together in one, so a read model can never see an assessment the
 * database does not hold (or the reverse). Joining a caller's transaction is fine: the event then commits with it.
 */
@Service
public class FairLoanCheckService {

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

        RunFairCheckCommand withMonthlyIncomes(List<IncomeMonthDto> verified) {
            return new RunFairCheckCommand(applicationId, purpose, requestedAmountCents, requestedTermMonths, verified,
                statedMonthlyIncomeCents, statedMonthlyExpensesCents, completedLoansOnTime, hasActiveLoan);
        }
    }

    private final UnderwritingRepository repository;
    private final SavingsFacade savingsFacade;
    private final FairCheckNarrator narrator;
    private final IncomeRefresher incomeRefresher;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    public FairLoanCheckService(UnderwritingRepository repository, SavingsFacade savingsFacade) {
        this(repository, savingsFacade, null);
    }

    public FairLoanCheckService(
        UnderwritingRepository repository,
        SavingsFacade savingsFacade,
        FairCheckNarrator narrator
    ) {
        this(repository, savingsFacade, narrator, null, null, null);
    }

    /** {@code incomeRefresher}, {@code events} and {@code transactions} may be null when running without a database. */
    @Autowired
    public FairLoanCheckService(
        UnderwritingRepository repository,
        SavingsFacade savingsFacade,
        @Autowired(required = false) FairCheckNarrator narrator,
        @Autowired(required = false) IncomeRefresher incomeRefresher,
        @Autowired(required = false) ApplicationEventPublisher events,
        @Autowired(required = false) PlatformTransactionManager transactions
    ) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.savingsFacade = Objects.requireNonNull(savingsFacade, "savingsFacade is required");
        this.narrator = narrator;
        this.incomeRefresher = incomeRefresher;
        this.events = events;
        this.transaction = transactions == null ? null : new TransactionTemplate(transactions);
    }

    public ConsentStatusView grantConsent(UUID memberId) {
        Objects.requireNonNull(memberId, "memberId is required");
        ConsentRecord saved = repository.saveConsent(memberId, true, Instant.now());
        return toConsentView(saved);
    }

    public ConsentStatusView revokeConsent(UUID memberId) {
        Objects.requireNonNull(memberId, "memberId is required");
        ConsentRecord saved = repository.saveConsent(memberId, false, Instant.now());
        return toConsentView(saved);
    }

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

        // With the real income source the figures come from PayPal, not from the request, and a failed read refuses
        // the check (retryable) instead of falling back to numbers the member typed.
        RunFairCheckCommand effective = incomeRefresher == null ? command
            : incomeRefresher.verifiedMonthlyIncomes(memberId).map(command::withMonthlyIncomes).orElse(command);

        MemberSavingsSummary savings = savingsFacade.getMemberSavingsSummary(memberId.toString());
        List<MonthlyIncomePoint> incomePoints = effective.monthlyIncomes().stream()
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

        applyNarrationIfPresent(assessment, null);
        commit(assessment);
        return toCardView(assessment);
    }

    public FairCheckCardView disputeAssessment(UUID assessmentId, UUID memberId, String note) {
        FairLoanCheckAssessment assessment = findOrThrow(assessmentId);
        if (!assessment.getMemberId().equals(memberId)) {
            throw new DomainException(UnderwritingErrors.ASSESSMENT_NOT_FOUND, "Assessment not found");
        }
        assessment.flagMemberDispute(note, Instant.now());
        applyNarrationIfPresent(assessment, assessment.getMemberDisputeNote());
        commit(assessment);
        return toCardView(assessment);
    }

    private void applyNarrationIfPresent(FairLoanCheckAssessment assessment, String disputeNote) {
        if (narrator == null) {
            return;
        }
        FairCheckResult r = assessment.getResult();
        NarrationResult narrated = narrator.narrate(new NarrationInput(
            assessment.getId(),
            assessment.getApplicationId(),
            assessment.getPurpose(),
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
            disputeNote,
            r.plainLanguageReasons(),
            r.officerQuestions()
        ));
        if (narrated == null) {
            return;
        }
        List<String> reasons = new ArrayList<>(narrated.plainLanguageReasons());
        if (disputeNote != null && !disputeNote.isBlank()) {
            String bannerPrefix = "Member disputed the figures:";
            boolean hasBanner = reasons.stream().anyMatch(s -> s.startsWith(bannerPrefix));
            if (!hasBanner) {
                reasons.add(bannerPrefix + " \"" + disputeNote + "\" — flagged for manual loan-officer conversation.");
            }
        }
        assessment.withGroundedNarration(reasons, narrated.officerQuestions());
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
        commit(assessment);
        return toCardView(assessment);
    }

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

    /** The newest assessment as a status a member may see, without scores, tiers or reasons. */
    public Optional<AssessmentStatusView> latestAssessmentStatus(UUID memberId) {
        return repository.findAssessmentsByMemberId(memberId).stream().findFirst()
            .map(a -> new AssessmentStatusView(a.getId(), a.getOfficerDecision().name(), a.isMemberDisputed(), a.getCreatedAt()));
    }

    private void commit(FairLoanCheckAssessment assessment) {
        if (transaction == null) {
            saveAndAnnounce(assessment);
        } else {
            transaction.executeWithoutResult(status -> saveAndAnnounce(assessment));
        }
    }

    private void saveAndAnnounce(FairLoanCheckAssessment assessment) {
        repository.saveAssessment(assessment);
        if (events != null) {
            events.publishEvent(changed(assessment));
        }
    }

    static AssessmentChanged changed(FairLoanCheckAssessment a) {
        FairCheckResult r = a.getResult();
        Instant changedAt = a.getDecidedAt() != null ? a.getDecidedAt() : a.getCreatedAt();
        return new AssessmentChanged(Ids.newId(), a.getId(), a.getMemberId(), a.getApplicationId(), r.recommended(),
            r.suggestedLoanCents(), a.getOfficerDecision().name(), a.isMemberDisputed(), a.getCreatedAt(), changedAt);
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
