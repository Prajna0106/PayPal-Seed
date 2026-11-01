package com.paypalseed.app.home;

import com.paypalseed.app.home.HomeView.ActiveLoanCard;
import com.paypalseed.app.home.HomeView.AssessmentStatus;
import com.paypalseed.app.home.HomeView.AutopayCard;
import com.paypalseed.app.home.HomeView.CreditRecordSection;
import com.paypalseed.app.home.HomeView.EligibilityCard;
import com.paypalseed.app.home.HomeView.GoalCard;
import com.paypalseed.app.home.HomeView.HardshipSection;
import com.paypalseed.app.home.HomeView.LendingSection;
import com.paypalseed.app.home.HomeView.MoneyView;
import com.paypalseed.app.home.HomeView.NextPayment;
import com.paypalseed.app.home.HomeView.NextStep;
import com.paypalseed.app.home.HomeView.OpenApplicationCard;
import com.paypalseed.app.home.HomeView.ProfileSection;
import com.paypalseed.app.home.HomeView.SavingsSection;
import com.paypalseed.app.home.HomeView.UnderwritingSection;
import com.paypalseed.creditrecord.api.CreditRecordFacade;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.NextStepView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.RecordView;
import com.paypalseed.hardship.api.dto.HardshipDtos.MemberCaseView;
import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberLoanView;
import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.MandateView;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.ConsentStatusView;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Reads each section from the owning module's public facade and maps it as given. Nothing is computed here: money,
 * dates, eligibility and the next payment come from their modules, and no risk level, score or tier is read at all.
 */
@Component
class FacadeHomeSections implements HomeSections {

    private static final Set<String> OPEN_APPLICATION =
        Set.of("SUBMITTED", "PENDING_SECOND_APPROVAL", "APPROVED", "DISBURSING");

    private final IdentityFacade identity;
    private final SavingsFacade savings;
    private final PaymentsFacade payments;
    private final UnderwritingFacade underwriting;
    private final CreditRecordFacade creditRecord;
    private final ObjectProvider<MemberReads> reads;

    FacadeHomeSections(IdentityFacade identity, SavingsFacade savingsFacade, PaymentsFacade payments,
                       UnderwritingFacade underwriting, CreditRecordFacade creditRecord,
                       ObjectProvider<MemberReads> reads) {
        this.identity = identity;
        this.savings = savingsFacade;
        this.payments = payments;
        this.underwriting = underwriting;
        this.creditRecord = creditRecord;
        this.reads = reads;
    }

    private MemberReads memberReads() {
        MemberReads available = reads.getIfAvailable();
        if (available == null) {
            throw new IllegalStateException("per-member reads are not wired");
        }
        return available;
    }

    @Override
    public Optional<ProfileSection> profile(UUID memberId) {
        return identity.member(memberId)
            .map(m -> new ProfileSection(m.displayName(), m.language().code(), m.walletPreference().name()));
    }

    @Override
    public Optional<SavingsSection> savings(Context context) {
        MemberSavingsSummary summary = savings.getMemberSavingsSummary(context.memberId().toString());
        List<GoalCard> goals = null;
        MemberReads available = reads.getIfAvailable();
        if (available != null) {
            goals = available.goals(context.memberId()).stream().map(FacadeHomeSections::goalCard).toList();
        }
        return Optional.of(new SavingsSection(summary.currentStreakMonths(), MoneyView.usd(summary.monthlySavingCents()),
            MoneyView.usd(summary.totalSavedBalanceCents()), summary.missedDepositsInQuarter(),
            summary.microloanEligible(), goals));
    }

    @Override
    public Optional<LendingSection> lending(Context context) {
        MemberReads source = memberReads();
        UUID memberId = context.memberId();
        EligibilityView eligibility = source.eligibility(memberId);
        Optional<MemberLoanView> active =
            source.loans(memberId).stream().filter(l -> "ACTIVE".equals(l.status())).findFirst();
        OpenApplicationCard open = source.applications(memberId).stream()
            .filter(a -> OPEN_APPLICATION.contains(a.status()))
            .findFirst().map(FacadeHomeSections::openApplication).orElse(null);
        return Optional.of(new LendingSection(eligibilityCard(eligibility), active.map(this::activeLoan).orElse(null), open));
    }

    private ActiveLoanCard activeLoan(MemberLoanView loan) {
        NextPayment next = loan.nextDue() == null ? null
            : new NextPayment(loan.nextDue().seq(), loan.nextDue().dueDate(), MoneyView.usd(loan.nextDue().amountCents()),
                MoneyView.usd(loan.nextDue().remainingCents()));
        AutopayCard autopay = payments.mandateOf(loan.loanId()).map(FacadeHomeSections::autopay).orElse(null);
        return new ActiveLoanCard(loan.loanId(), loan.status(), MoneyView.usd(loan.outstandingCents()), next, autopay);
    }

    @Override
    public Optional<UnderwritingSection> underwriting(Context context) {
        ConsentStatusView consent = underwriting.getConsentStatus(context.memberId());
        AssessmentStatus latest = underwriting.latestAssessmentStatus(context.memberId())
            .map(a -> new AssessmentStatus(a.assessmentId(), a.decision(), a.memberDisputed(), a.createdAt()))
            .orElse(null);
        return Optional.of(new UnderwritingSection(consent.consented(), latest));
    }

    @Override
    public Optional<CreditRecordSection> creditRecord(Context context) {
        RecordView record = creditRecord.record(context.memberId());
        NextStepView next = record.next();
        NextStep step = next == null ? null : new NextStep(next.level(), next.onTimeSavingMonthsToGo(),
            next.loansRepaidOnPlanToGo(), NextStepHint.of(next, context.language()));
        return Optional.of(new CreditRecordSection(record.level(), record.since(), step));
    }

    @Override
    public Optional<HardshipSection> hardship(Context context) {
        Optional<MemberCaseView> open = memberReads().openHardshipCase(context.memberId());
        return Optional.of(open
            .map(c -> new HardshipSection(c.offerWaiting(), c.caseId(), c.loanId(), c.status(), c.dueDate(),
                MoneyView.usd(c.amountAtStakeCents())))
            .orElseGet(() -> new HardshipSection(false, null, null, null)));
    }

    private static GoalCard goalCard(MemberReads.Goal goal) {
        return new GoalCard(goal.goalId(), goal.name(), goal.status(),
            new MoneyView(goal.targetCents(), goal.currency()), new MoneyView(goal.savedCents(), goal.currency()),
            new MoneyView(goal.monthlyCents(), goal.currency()), goal.targetDate());
    }

    private static EligibilityCard eligibilityCard(EligibilityView e) {
        return new EligibilityCard(e.eligible(), MoneyView.usd(e.maxLoanCents()),
            MoneyView.usd(e.maxMonthlyInstalmentCents()), e.minTermMonths(), e.maxTermMonths(), e.reasons());
    }

    private static OpenApplicationCard openApplication(MemberApplicationView a) {
        return new OpenApplicationCard(a.applicationId(), a.status(), MoneyView.usd(a.requestedAmountCents()), a.termMonths(),
            a.coolingOffExpiresAt(), a.payoutIssue());
    }

    private static AutopayCard autopay(MandateView mandate) {
        return new AutopayCard(mandate.status().name(), mandate.nextBillingDate().orElse(null));
    }
}
