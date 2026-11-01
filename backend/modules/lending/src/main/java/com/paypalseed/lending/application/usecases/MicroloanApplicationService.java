package com.paypalseed.lending.application.usecases;

import com.paypalseed.creditrecord.api.CreditRecordFacade;
import com.paypalseed.engines.rules.EligibilityEngine;
import com.paypalseed.engines.rules.EligibilityEngine.EligibilityEvaluation;
import com.paypalseed.engines.rules.EligibilityEngine.EligibilityInput;
import com.paypalseed.engines.rules.EligibilityEngine.SizingDecision;
import com.paypalseed.engines.rules.record.RecordLevel;
import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.ledger.api.dto.PostingRequest;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.RegisterDisbursedLoan;
import com.paypalseed.lending.api.events.LoanApplicationChanged;
import com.paypalseed.lending.application.ports.DisbursementPayouts;
import com.paypalseed.lending.application.ports.LoanApplicationRepository;
import com.paypalseed.lending.application.ports.PayoutRecipients;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.lending.domain.model.LoanApplication;
import com.paypalseed.lending.domain.model.LoanApplication.ApplicationStatus;
import com.paypalseed.lending.domain.model.StaffReferences;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.LoanStatus;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service orchestrating Feature 2: Savings-linked microloans (Blueprint §5).
 * Evaluates ladder eligibility from savings streaks and consented underwriting income, enforces
 * sizing rules, manages two-officer maker-checker approval, and registers disbursed 0% loans into
 * the schedule & repayment engine.
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

    private static final Logger log = LoggerFactory.getLogger(MicroloanApplicationService.class);

    private final LoanApplicationRepository applications;
    private final SavingsFacade savingsFacade;
    private final LoanRepository loans;
    private final LendingFacade lendingFacade;
    private final BusinessClock clock;
    private final UnderwritingFacade underwritingFacade;
    private final LedgerFacade ledgerFacade;
    private final DisbursementPayouts payouts;
    private final PayoutRecipients recipients;
    private final ApplicationEventPublisher events;
    private final CreditRecordFacade creditRecord;

    public MicroloanApplicationService(
        LoanApplicationRepository applications,
        SavingsFacade savingsFacade,
        LoanRepository loans,
        LendingFacade lendingFacade,
        BusinessClock clock
    ) {
        this(applications, savingsFacade, loans, lendingFacade, clock, null, null);
    }

    /** Without the payments wiring: an approved application is disbursed at once, as before payouts went through PayPal. */
    public MicroloanApplicationService(
        LoanApplicationRepository applications,
        SavingsFacade savingsFacade,
        LoanRepository loans,
        LendingFacade lendingFacade,
        BusinessClock clock,
        UnderwritingFacade underwritingFacade,
        LedgerFacade ledgerFacade
    ) {
        this(applications, savingsFacade, loans, lendingFacade, clock, underwritingFacade, ledgerFacade, null, null, null);
    }

    /** Without a credit record: the ladder alone sizes the loan, as before the record existed. */
    public MicroloanApplicationService(
        LoanApplicationRepository applications,
        SavingsFacade savingsFacade,
        LoanRepository loans,
        LendingFacade lendingFacade,
        BusinessClock clock,
        UnderwritingFacade underwritingFacade,
        LedgerFacade ledgerFacade,
        DisbursementPayouts payouts,
        PayoutRecipients recipients,
        ApplicationEventPublisher events
    ) {
        this(applications, savingsFacade, loans, lendingFacade, clock, underwritingFacade, ledgerFacade, payouts, recipients, events, null);
    }

    @Autowired
    public MicroloanApplicationService(
        LoanApplicationRepository applications,
        SavingsFacade savingsFacade,
        @Autowired(required = false) LoanRepository loans,
        @Autowired(required = false) LendingFacade lendingFacade,
        @Autowired(required = false) BusinessClock clock,
        @Autowired(required = false) UnderwritingFacade underwritingFacade,
        @Autowired(required = false) LedgerFacade ledgerFacade,
        @Autowired(required = false) DisbursementPayouts payouts,
        @Autowired(required = false) PayoutRecipients recipients,
        @Autowired(required = false) ApplicationEventPublisher events,
        @Autowired(required = false) CreditRecordFacade creditRecord
    ) {
        this.applications = Objects.requireNonNull(applications, "applications repository is required");
        this.savingsFacade = Objects.requireNonNull(savingsFacade, "savingsFacade is required");
        this.loans = loans;
        this.lendingFacade = lendingFacade;
        this.clock = clock;
        this.underwritingFacade = underwritingFacade;
        this.ledgerFacade = ledgerFacade;
        this.payouts = payouts;
        this.recipients = recipients;
        this.events = events;
        this.creditRecord = creditRecord;
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
            eval.reasons(),
            eval.recordLevel().name(),
            eval.limitReason()
        );
    }

    @Transactional
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

        // Automatically record the linked F3 Fair Loan Check assessment when consented
        if (command.fairCheckConsented() && underwritingFacade != null) {
            long incomeCents = input.verifiedMonthlyIncomeCents().orElse(0L);
            underwritingFacade.assessForLoanApplication(
                memberId,
                app.getId(),
                app.getPurpose(),
                app.getRequestedAmountCents(),
                app.getTermMonths(),
                incomeCents,
                input.completedLoansOnTime(),
                input.hasActiveLoan()
            );
        }

        publishChanged(app);
        return toView(app);
    }

    @Transactional
    public LoanApplicationView approveApplication(UUID applicationId, UUID officerId) {
        LoanApplication app = findForUpdate(applicationId);
        app.approve(officerId, Instant.now());
        applications.save(app);
        publishChanged(app);
        return toView(app);
    }

    @Transactional
    public LoanApplicationView rejectApplication(UUID applicationId, UUID officerId, String reason) {
        LoanApplication app = findForUpdate(applicationId);
        app.reject(officerId, reason, Instant.now());
        applications.save(app);
        publishChanged(app);
        return toView(app);
    }

    /**
     * Disburses an approved loan (Blueprint §5: {@code sender_batch_id = loan-{id}-disb}). With PayPal wired in, the
     * application moves to {@code DISBURSING} and the member is paid through Payouts with the application id as the
     * payout reference; the loan and Schedule Version 1 are registered when the payout succeeds, never before. A
     * payout PayPal answers at once is completed in this call; otherwise the payout event completes it. Asking again
     * while disbursing asks PayPal for the same payout, and asking for a disbursed application returns it.
     */
    @Transactional
    public LoanApplicationView disburseApplication(UUID applicationId) {
        return disburseApplication(applicationId, null);
    }

    /** {@code officerId} is the officer who pressed the button, kept for the desk queue; it may be null. */
    @Transactional
    public LoanApplicationView disburseApplication(UUID applicationId, UUID officerId) {
        LoanApplication app = findForUpdate(applicationId);
        if (app.getStatus() == ApplicationStatus.DISBURSED) {
            return toView(app);
        }
        if (payouts == null || recipients == null) {
            return disburseWithoutPayout(app);
        }
        if (app.getStatus() != ApplicationStatus.APPROVED && app.getStatus() != ApplicationStatus.DISBURSING) {
            throw new DomainException(
                LendingErrors.APPLICATION_INVALID_STATE,
                "Loan application must be APPROVED by two officers before disbursement; current status: " + app.getStatus()
            );
        }
        PayoutRecipients.Recipient recipient = recipients.of(app.getMemberId())
            .orElseThrow(() -> new DomainException(LendingErrors.PAYOUT_RECIPIENT_MISSING,
                "The member has no PayPal account on file to receive the loan"));
        boolean firstAsk = app.getStatus() == ApplicationStatus.APPROVED;
        app.startDisbursement(officerId, Instant.now());
        applications.save(app);
        if (firstAsk) {
            publishChanged(app);
        }
        DisbursementPayouts.Result result = payouts.send(app.payoutReference(), app.getRequestedAmountCents(), recipient,
            app.getPayoutWallet(), "Seed microloan");
        switch (result.state()) {
            case SUCCEEDED -> complete(app);
            case FAILED -> fail(app, result.reasonCode());
            case PENDING -> {
                // PayPal reports the end by webhook; the application stays DISBURSING until then
            }
        }
        return toView(app);
    }

    /** The payout reached the member. Idempotent: an application already disbursed, or another payout''s reference, changes nothing. */
    @Transactional
    public void onPayoutSucceeded(String payoutReference) {
        currentPayout(payoutReference).ifPresent(app -> {
            if (app.getStatus() == ApplicationStatus.DISBURSING) {
                complete(app);
            }
        });
    }

    /**
     * The payout did not reach the member. A terminal failure puts the application back to {@code APPROVED} with the
     * reason for an officer; a non-terminal one (money held for a recipient with no account yet) only notes it and
     * waits for the success or the return that follows.
     */
    @Transactional
    public void onPayoutFailed(String payoutReference, String reasonCode, boolean terminal) {
        currentPayout(payoutReference).ifPresent(app -> {
            if (app.getStatus() != ApplicationStatus.DISBURSING) {
                return;
            }
            if (terminal) {
                fail(app, reasonCode);
            } else {
                app.notePayoutIssue(reasonCode, Instant.now());
                applications.save(app);
            }
        });
    }

    /** PayPal took the payout back (unclaimed money expired, or a delivered payout was reversed). */
    @Transactional
    public void onPayoutReturned(String payoutReference, String reasonCode) {
        currentPayout(payoutReference).ifPresent(app -> {
            if (app.getStatus() == ApplicationStatus.DISBURSING) {
                fail(app, reasonCode == null ? "RETURNED" : reasonCode);
            } else if (app.getStatus() == ApplicationStatus.DISBURSED) {
                // The loan is registered and the member has the schedule, but the money is back in the program account.
                // Unwinding a registered loan is a decision for people; say so loudly and show the desk.
                log.error("Payout {} of disbursed application {} was returned ({}); the loan needs an officer", payoutReference,
                    app.getId(), reasonCode);
                app.notePayoutIssue("RETURNED_AFTER_DISBURSEMENT", Instant.now());
                applications.save(app);
                publishChanged(app);
            }
        });
    }

    /** Registers the loan exactly as {@code /internal/loans/disbursed} does: same use case, schedule and ledger postings. */
    private void complete(LoanApplication app) {
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
        publishChanged(app);
    }

    private void fail(LoanApplication app, String reasonCode) {
        app.payoutFailed(reasonCode, Instant.now());
        applications.save(app);
        publishChanged(app);
    }

    /** The behaviour before payouts went through PayPal: register the loan now. */
    private LoanApplicationView disburseWithoutPayout(LoanApplication app) {
        if (app.getStatus() != ApplicationStatus.APPROVED) {
            throw new DomainException(
                LendingErrors.APPLICATION_INVALID_STATE,
                "Loan application must be APPROVED by two officers before disbursement; current status: " + app.getStatus()
            );
        }
        complete(app);
        return toView(app);
    }

    /** The application a payout event is about, if the event is about the application''s current payout. */
    private java.util.Optional<LoanApplication> currentPayout(String payoutReference) {
        if (payoutReference == null || payoutReference.length() < 36) {
            return java.util.Optional.empty();
        }
        UUID applicationId;
        try {
            applicationId = UUID.fromString(payoutReference.substring(0, 36));
        } catch (IllegalArgumentException notOurs) {
            return java.util.Optional.empty(); // a savings withdrawal or something else paid through the same account
        }
        return applications.findByIdForUpdate(applicationId).filter(app -> payoutReference.equals(app.payoutReference()));
    }
    @Transactional
    public LoanApplicationView cancelWithinCoolingOff(UUID applicationId, UUID memberId) {
        LoanApplication app = findForUpdate(applicationId);
        if (!app.getMemberId().equals(memberId)) {
            throw new DomainException(LendingErrors.APPLICATION_NOT_FOUND, "Loan application not found");
        }
        app.cancelWithinCoolingOff(Instant.now());
        applications.save(app);
        publishChanged(app);

        if (ledgerFacade != null && app.getLoanId() != null) {
            LocalDate businessDate = clock != null ? clock.today() : LocalDate.now();
            ledgerFacade.post(PostingRequest.builder(
                    "loan-cooling-off:" + app.getId(),
                    "48h cooling-off return for loan " + app.getLoanId(),
                    businessDate)
                .debit(AccountRef.program(AccountType.PROGRAM_CASH), app.getRequestedAmountCents())
                .credit(AccountRef.of(AccountType.LOANS_RECEIVABLE, app.getLoanId()), app.getRequestedAmountCents())
                .build());
        }

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

        OptionalLong resolvedIncome = verifiedMonthlyIncomeCents != null ? verifiedMonthlyIncomeCents : OptionalLong.empty();
        if (resolvedIncome.isEmpty() && underwritingFacade != null) {
            LocalDate today = clock != null ? clock.today() : LocalDate.now();
            resolvedIncome = underwritingFacade.verifiedMonthlyIncomeCents(memberId, today);
        }

        return new EligibilityInput(
            savings.currentStreakMonths(),
            savings.totalOnTimeDeposits(),
            savings.missedDepositsInQuarter(),
            savings.monthlySavingCents(),
            resolvedIncome,
            completedLoansOnTime,
            hasActiveLoan,
            recordLevelOf(memberId)
        );
    }

    /** The level of the member's credit record; NEW when there is no record or it cannot be read. */
    private RecordLevel recordLevelOf(UUID memberId) {
        if (creditRecord == null) {
            return RecordLevel.NEW;
        }
        return RecordLevel.ofRank(creditRecord.levelOf(memberId).levelRank());
    }

    private LoanApplication findForUpdate(UUID applicationId) {
        return applications.findByIdForUpdate(applicationId)
            .orElseThrow(() -> new DomainException(LendingErrors.APPLICATION_NOT_FOUND, "Loan application not found: " + applicationId));
    }

    private void publishChanged(LoanApplication app) {
        if (events == null) {
            return;
        }
        UUID officer = app.getLastOfficerId();
        events.publishEvent(new LoanApplicationChanged(Ids.newId(), app.getId(), app.getMemberId(), app.getStatus().name(),
            app.getRequestedAmountCents(), app.getTermMonths(), app.getCreatedAt(),
            officer == null ? null : StaffReferences.of(officer), app.getUpdatedAt()));
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
            app.getUpdatedAt(),
            app.getPayoutIssue()
        );
    }
}
