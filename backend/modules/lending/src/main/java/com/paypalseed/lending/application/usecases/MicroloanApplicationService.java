package com.paypalseed.lending.application.usecases;

import com.paypalseed.engines.rules.EligibilityEngine;
import com.paypalseed.engines.rules.EligibilityEngine.EligibilityEvaluation;
import com.paypalseed.engines.rules.EligibilityEngine.EligibilityInput;
import com.paypalseed.engines.rules.EligibilityEngine.SizingDecision;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.RegisterDisbursedLoan;
import com.paypalseed.lending.application.ports.LoanApplicationRepository;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.lending.domain.model.LoanApplication;
import com.paypalseed.lending.domain.model.LoanApplication.ApplicationStatus;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.LoanStatus;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Application service orchestrating Feature 2: Savings-linked microloans (Blueprint §5).
 * Evaluates ladder eligibility from savings streaks, enforces sizing rules, manages two-officer
 * maker-checker approval, and registers disbursed 0% loans into the schedule & repayment engine.
 */
@Service
public class MicroloanApplicationService {

    public record SubmitApplicationCommand(
        String purpose,
        long requestedAmountCents,
        int termMonths,
        Integer preferredDueDay,
        WalletType payoutWallet,
        boolean fairCheckConsented,
        OptionalLong verifiedMonthlyIncomeCents
    ) {
        public SubmitApplicationCommand {
            if (verifiedMonthlyIncomeCents == null) {
                verifiedMonthlyIncomeCents = OptionalLong.empty();
            }
        }
    }

    private final LoanApplicationRepository applications;
    private final SavingsFacade savingsFacade;
    private final LoanRepository loans;
    private final LendingFacade lendingFacade;
    private final BusinessClock clock;

    @Autowired
    public MicroloanApplicationService(
        LoanApplicationRepository applications,
        SavingsFacade savingsFacade,
        @Autowired(required = false) LoanRepository loans,
        @Autowired(required = false) LendingFacade lendingFacade,
        @Autowired(required = false) BusinessClock clock
    ) {
        this.applications = Objects.requireNonNull(applications, "applications repository is required");
        this.savingsFacade = Objects.requireNonNull(savingsFacade, "savingsFacade is required");
        this.loans = loans;
        this.lendingFacade = lendingFacade;
        this.clock = clock;
    }

    public EligibilityView checkEligibility(UUID memberId, OptionalLong verifiedMonthlyIncomeCents) {
        Objects.requireNonNull(memberId, "memberId is required");
        EligibilityInput input = buildEligibilityInput(memberId, verifiedMonthlyIncomeCents);
        EligibilityEvaluation eval = EligibilityEngine.evaluate(input);

        return new EligibilityView(
            memberId,
            eval.eligible(),
            eval.tier().name(),
            eval.tier().level(),
            eval.tierLimitCents(),
            eval.maxLoanCents(),
            eval.maxMonthlyInstalmentCents(),
            eval.minTermMonths(),
            eval.maxTermMonths(),
            input.currentStreakMonths(),
            input.monthlySavingCents(),
            input.hasActiveLoan(),
            input.completedLoansOnTime(),
            eval.reasons()
        );
    }

    public LoanApplicationView submitApplication(UUID memberId, SubmitApplicationCommand command) {
        Objects.requireNonNull(memberId, "memberId is required");
        Objects.requireNonNull(command, "command is required");

        EligibilityInput input = buildEligibilityInput(memberId, command.verifiedMonthlyIncomeCents());
        EligibilityEvaluation eval = EligibilityEngine.evaluate(input);

        if (!eval.eligible()) {
            throw new DomainException(
                LendingErrors.INELIGIBLE_FOR_LOAN,
                String.join(" ", eval.reasons()),
                Map.of(
                    "tier", eval.tier().name(),
                    "reasons", eval.reasons()
                )
            );
        }

        SizingDecision decision = EligibilityEngine.checkApplication(
            input,
            command.requestedAmountCents(),
            command.termMonths()
        );

        if (!decision.allowed()) {
            throw new DomainException(
                LendingErrors.SIZING_RULE_VIOLATION,
                String.join(" ", decision.reasons()),
                Map.of(
                    "requestedAmountCents", decision.requestedPrincipalCents(),
                    "requestedTermMonths", decision.requestedTermMonths(),
                    "requiredMonthlyInstalmentCents", decision.requiredMonthlyInstalmentCents(),
                    "maxMonthlyInstalmentCents", decision.maxMonthlyInstalmentCents(),
                    "suggestedPrincipalCents", decision.suggestedPrincipalCents(),
                    "suggestedTermMonths", decision.suggestedTermMonths(),
                    "reasons", decision.reasons()
                )
            );
        }

        int dueDay = command.preferredDueDay() != null ? command.preferredDueDay() : 15;
        WalletType wallet = command.payoutWallet() != null ? command.payoutWallet() : WalletType.PAYPAL;

        LoanApplication app = LoanApplication.submit(
            memberId,
            command.purpose(),
            command.requestedAmountCents(),
            command.termMonths(),
            dueDay,
            wallet,
            command.fairCheckConsented(),
            eval.tier(),
            eval.maxMonthlyInstalmentCents(),
            decision.requiredMonthlyInstalmentCents(),
            decision.reasons(),
            Instant.now()
        );

        applications.save(app);
        return toView(app);
    }

    public LoanApplicationView approveApplication(UUID applicationId, UUID officerId) {
        LoanApplication app = findOrThrow(applicationId);
        app.approve(officerId, Instant.now());
        applications.save(app);
        return toView(app);
    }

    public LoanApplicationView rejectApplication(UUID applicationId, UUID officerId, String reason) {
        LoanApplication app = findOrThrow(applicationId);
        app.reject(officerId, reason, Instant.now());
        applications.save(app);
        return toView(app);
    }

    /**
     * Disburses an approved loan (Blueprint §5: {@code sender_batch_id = loan-{id}-disb})
     * and registers the loan and Schedule Version 1 in the lending engine.
     */
    public LoanApplicationView disburseApplication(UUID applicationId) {
        LoanApplication app = findOrThrow(applicationId);
        if (app.getStatus() != ApplicationStatus.APPROVED) {
            throw new DomainException(
                LendingErrors.APPLICATION_INVALID_STATE,
                "Loan application must be APPROVED by two officers before disbursement; current status: " + app.getStatus()
            );
        }

        String batchId = app.expectedDisbursementBatchId();
        LocalDate disbursedOn = clock != null ? clock.today() : LocalDate.now();
        UUID registeredLoanId;

        if (lendingFacade != null) {
            LoanSummary summary = lendingFacade.registerDisbursedLoan(new RegisterDisbursedLoan(
                app.getMemberId(),
                app.getRequestedAmountCents(),
                app.getTermMonths(),
                app.getPreferredDueDay(),
                disbursedOn,
                batchId
            ));
            registeredLoanId = summary.loanId();
        } else {
            registeredLoanId = Ids.newId();
        }

        app.markDisbursed(registeredLoanId, batchId, Instant.now());
        applications.save(app);
        return toView(app);
    }

    public LoanApplicationView cancelWithinCoolingOff(UUID applicationId, UUID memberId) {
        LoanApplication app = findOrThrow(applicationId);
        if (!app.getMemberId().equals(memberId)) {
            throw new DomainException(LendingErrors.APPLICATION_NOT_FOUND, "Loan application not found");
        }
        app.cancelWithinCoolingOff(Instant.now());
        applications.save(app);
        return toView(app);
    }

    public LoanApplicationView getApplication(UUID applicationId) {
        return toView(findOrThrow(applicationId));
    }

    public LoanApplicationView getMemberApplication(UUID applicationId, UUID memberId) {
        LoanApplication app = findOrThrow(applicationId);
        if (!app.getMemberId().equals(memberId)) {
            throw new DomainException(LendingErrors.APPLICATION_NOT_FOUND, "Loan application not found");
        }
        return toView(app);
    }

    public List<LoanApplicationView> listMemberApplications(UUID memberId) {
        return applications.findByMemberId(memberId).stream()
            .map(MicroloanApplicationService::toView)
            .toList();
    }

    public List<LoanApplicationView> listAllApplications() {
        return applications.findAll().stream()
            .map(MicroloanApplicationService::toView)
            .toList();
    }

    private EligibilityInput buildEligibilityInput(UUID memberId, OptionalLong verifiedMonthlyIncomeCents) {
        MemberSavingsSummary savings = savingsFacade.getMemberSavingsSummary(memberId.toString());

        boolean hasActiveLoan = false;
        int completedLoansOnTime = 0;

        if (loans != null) {
            try {
                List<LoanRow> memberLoans = loans.byMember(memberId);
                hasActiveLoan = memberLoans.stream().anyMatch(LoanRow::isActive);
                completedLoansOnTime = (int) memberLoans.stream()
                    .filter(l -> l.status() == LoanStatus.REPAID)
                    .count();
            } catch (Exception ignored) {
                // Fallback when running without Postgres
            }
        }

        if (!hasActiveLoan) {
            hasActiveLoan = applications.findByMemberId(memberId).stream()
                .anyMatch(a -> a.getStatus() == ApplicationStatus.DISBURSED);
        }

        return new EligibilityInput(
            savings.currentStreakMonths(),
            savings.totalOnTimeDeposits(),
            savings.missedDepositsInQuarter(),
            savings.monthlySavingCents(),
            verifiedMonthlyIncomeCents != null ? verifiedMonthlyIncomeCents : OptionalLong.empty(),
            completedLoansOnTime,
            hasActiveLoan
        );
    }

    private LoanApplication findOrThrow(UUID applicationId) {
        return applications.findById(applicationId)
            .orElseThrow(() -> new DomainException(LendingErrors.APPLICATION_NOT_FOUND, "Loan application not found: " + applicationId));
    }

    public static LoanApplicationView toView(LoanApplication app) {
        return new LoanApplicationView(
            app.getId(),
            app.getMemberId(),
            app.getPurpose(),
            app.getRequestedAmountCents(),
            app.getTermMonths(),
            app.getPreferredDueDay(),
            app.getPayoutWallet().name(),
            app.isFairCheckConsented(),
            app.getUnlockedTier().name(),
            app.getMaxMonthlyInstalmentCents(),
            app.getMonthlyInstalmentCents(),
            app.getStatus().name(),
            app.getFirstApproverId(),
            app.getSecondApproverId(),
            app.getRejectionReason(),
            app.getLoanId(),
            app.getDisbursementRef(),
            app.getCoolingOffExpiresAt(),
            app.getSizingReasons(),
            app.getCreatedAt(),
            app.getUpdatedAt()
        );
    }
}
