package com.paypalseed.lending;

import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.ApplicationPreviewView;
import com.paypalseed.lending.api.dto.LendingDtos.CoverResult;
import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.MemberApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberLoanView;
import com.paypalseed.lending.api.dto.LendingDtos.PreviewInstalment;
import com.paypalseed.lending.api.dto.LendingDtos.RegisterDisbursedLoan;
import com.paypalseed.lending.api.dto.LendingDtos.ReschedulePreview;
import com.paypalseed.lending.api.dto.LendingDtos.RescheduleRequest;
import com.paypalseed.lending.api.dto.LendingDtos.ReserveCover;
import com.paypalseed.lending.api.dto.LendingDtos.RiskFacts;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.lending.api.dto.LendingDtos.Slot;
import com.paypalseed.lending.application.usecases.LoanRegistration;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.PreviewApplicationCommand;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.lending.infrastructure.persistence.InMemoryLoanApplicationRepository;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MicroloanApplicationServiceTest {

    private static final UUID ANA_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OFFICER_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OFFICER_2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private InMemoryLoanApplicationRepository repository;
    private StubSavingsFacade savingsFacade;
    private RecordingLendingFacade lendingFacade;
    private MicroloanApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryLoanApplicationRepository();
        savingsFacade = new StubSavingsFacade();
        lendingFacade = new RecordingLendingFacade();

        // Ana: 4 months saving $75/month ($300 total saved), 0 misses
        savingsFacade.summaries.put(
            ANA_ID.toString(),
            new MemberSavingsSummary(ANA_ID.toString(), 4, 4, 0, 7_500L, 30_000L, true)
        );

        service = new MicroloanApplicationService(
            repository,
            savingsFacade,
            null,
            lendingFacade,
            new FakeBusinessClock(LocalDate.of(2026, 11, 1))
        );
    }

    @Test
    void endToEndAnaFlow_eligibilityToTwoOfficerApprovalAndDisbursement() {
        // 1. Check eligibility
        EligibilityView eligibility = service.checkEligibility(ANA_ID, OptionalLong.of(160_000L));
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.tier()).isEqualTo("LEVEL_1");
        assertThat(eligibility.tierLimitCents()).isEqualTo(150_000L);
        assertThat(eligibility.maxMonthlyInstalmentCents()).isEqualTo(15_000L);

        // 2. Submit $1,200 over 8 months for a commercial mixer
        LoanApplicationView submitted = service.submitApplication(
            ANA_ID,
            new SubmitApplicationCommand(
                "Commercial stand mixer for home bakery",
                120_000L,
                8,
                15,
                WalletType.VENMO,
                true,
                OptionalLong.of(160_000L)
            )
        );

        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(submitted.monthlyInstalmentCents()).isEqualTo(15_000L);
        assertThat(submitted.payoutWallet()).isEqualTo("VENMO");

        // 3. First officer approves -> PENDING_SECOND_APPROVAL
        LoanApplicationView firstApproved = service.approveApplication(submitted.id(), OFFICER_1);
        assertThat(firstApproved.status()).isEqualTo("PENDING_SECOND_APPROVAL");
        assertThat(firstApproved.firstApproverId()).isEqualTo(OFFICER_1);

        // 4. Maker-checker invariant: same officer cannot countersign
        assertThatThrownBy(() -> service.approveApplication(submitted.id(), OFFICER_1))
            .isInstanceOf(DomainException.class)
            .satisfies(ex -> assertThat(((DomainException) ex).errorCode()).isEqualTo(LendingErrors.MAKER_CHECKER_SAME_OFFICER));

        // 5. Second officer countersigns -> APPROVED
        LoanApplicationView secondApproved = service.approveApplication(submitted.id(), OFFICER_2);
        assertThat(secondApproved.status()).isEqualTo("APPROVED");
        assertThat(secondApproved.secondApproverId()).isEqualTo(OFFICER_2);

        // 6. Disburse -> registers loan with schedule engine and sets 48h cooling-off window
        LoanApplicationView disbursed = service.disburseApplication(submitted.id());
        assertThat(disbursed.status()).isEqualTo("DISBURSED");
        assertThat(disbursed.loanId()).isNotNull();
        assertThat(disbursed.disbursementRef()).isEqualTo("loan-" + submitted.id() + "-disb");
        assertThat(disbursed.coolingOffExpiresAt()).isNotNull();

        assertThat(lendingFacade.registeredLoans).hasSize(1);
        RegisterDisbursedLoan registered = lendingFacade.registeredLoans.get(0);
        assertThat(registered.memberId()).isEqualTo(ANA_ID);
        assertThat(registered.principalCents()).isEqualTo(120_000L);
        assertThat(registered.instalmentCount()).isEqualTo(8);
        assertThat(registered.dueDay()).isEqualTo(15);
    }

    @Test
    void blocksRequestExceedingSizingCapWithSuggestedSafeAlternative() {
        assertThatThrownBy(() -> service.submitApplication(
            ANA_ID,
            new SubmitApplicationCommand(
                "Bakery oven",
                120_000L,
                4, // $300/month > $150/month cap
                15,
                WalletType.PAYPAL,
                true,
                OptionalLong.of(160_000L)
            )
        ))
            .isInstanceOf(DomainException.class)
            .satisfies(ex -> {
                DomainException de = (DomainException) ex;
                assertThat(de.errorCode()).isEqualTo(LendingErrors.SIZING_RULE_VIOLATION);
                assertThat(de.details()).containsEntry("suggestedTermMonths", 8);
                assertThat(de.details()).containsEntry("suggestedPrincipalCents", 120_000L);
            });
    }

    @Test
    void coolingOffCancellation_allowsReturningFundsWithin48HoursWithNoCost() {
        LoanApplicationView submitted = service.submitApplication(
            ANA_ID,
            new SubmitApplicationCommand("Mixer", 120_000L, 8, 15, WalletType.VENMO, true, OptionalLong.empty())
        );
        service.approveApplication(submitted.id(), OFFICER_1);
        service.approveApplication(submitted.id(), OFFICER_2);
        service.disburseApplication(submitted.id());

        LoanApplicationView cancelled = service.cancelWithinCoolingOff(submitted.id(), ANA_ID);
        assertThat(cancelled.status()).isEqualTo("CANCELLED_COOLING_OFF");
    }

    @Test
    void previewIsTheScheduleDisbursementCreatesForTheSameApplication() {
        ApplicationPreviewView preview = service.previewApplication(ANA_ID,
            new PreviewApplicationCommand(120_000L, 8, 15, OptionalLong.of(160_000L)));

        assertThat(preview.valid()).isTrue();
        assertThat(preview.outcome()).isEqualTo("APPROVED");
        assertThat(preview.instalments()).hasSize(8);
        assertThat(preview.monthlyInstalmentCents()).isEqualTo(15_000L);
        assertThat(preview.totalRepayableCents()).isEqualTo(120_000L);
        assertThat(preview.instalments().stream().mapToLong(PreviewInstalment::amountCents).sum()).isEqualTo(120_000L);
        assertThat(preview.firstDueDate()).isEqualTo(preview.instalments().get(0).dueDate());
        assertThat(preview.assumedDisbursementDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(repository.findByMemberId(ANA_ID)).as("a preview stores nothing").isEmpty();
        assertThat(lendingFacade.registeredLoans).isEmpty();

        LoanApplicationView submitted = service.submitApplication(ANA_ID,
            new SubmitApplicationCommand("Mixer", 120_000L, 8, 15, WalletType.PAYPAL, true, OptionalLong.of(160_000L)));
        service.approveApplication(submitted.id(), OFFICER_1);
        service.approveApplication(submitted.id(), OFFICER_2);
        service.disburseApplication(submitted.id());

        RegisterDisbursedLoan registered = lendingFacade.registeredLoans.get(0);
        List<PreviewInstalment> created = LoanRegistration.originalSchedule(registered.principalCents(),
                registered.instalmentCount(), registered.dueDay(), registered.disbursedOn())
            .instalments().stream().map(i -> new PreviewInstalment(i.seq(), i.dueDate(), i.amountCents())).toList();
        assertThat(preview.instalments()).isEqualTo(created);
        assertThat(preview.dueDay()).isEqualTo(registered.dueDay());
    }

    @Test
    void previewOfAnAmountOverTheLimitSaysSoAndSuggestsASafeAlternative() {
        ApplicationPreviewView preview = service.previewApplication(ANA_ID,
            new PreviewApplicationCommand(160_000L, 8, null, OptionalLong.of(160_000L)));

        assertThat(preview.valid()).isFalse();
        assertThat(preview.outcome()).isEqualTo("ABOVE_LIMIT");
        assertThat(preview.instalments()).isEmpty();
        assertThat(preview.firstDueDate()).isNull();
        assertThat(preview.dueDay()).isEqualTo(15);
        assertThat(preview.limitCents()).isEqualTo(150_000L);
        assertThat(preview.suggestedAmountCents()).isEqualTo(150_000L);
        assertThat(preview.suggestedTermMonths()).isEqualTo(10);
        assertThat(preview.reasons()).singleElement().asString().contains("exceeds").contains("$1500.00");
    }

    @Test
    void previewOfATermOutsideTheRangeSuggestsTheNearestAllowedTerm() {
        ApplicationPreviewView preview = service.previewApplication(ANA_ID,
            new PreviewApplicationCommand(60_000L, 2, 15, OptionalLong.of(160_000L)));

        assertThat(preview.valid()).isFalse();
        assertThat(preview.outcome()).isEqualTo("TERM_OUT_OF_RANGE");
        assertThat(preview.instalments()).isEmpty();
        assertThat(preview.suggestedTermMonths()).isEqualTo(3);
        assertThat(preview.suggestedAmountCents()).isEqualTo(45_000L);
    }

    @Test
    void previewOfAnInstalmentAboveTheSafeCapSuggestsALongerTerm() {
        ApplicationPreviewView preview = service.previewApplication(ANA_ID,
            new PreviewApplicationCommand(120_000L, 4, 15, OptionalLong.of(160_000L)));

        assertThat(preview.valid()).isFalse();
        assertThat(preview.outcome()).isEqualTo("INSTALMENT_ABOVE_CAP");
        assertThat(preview.monthlyInstalmentCents()).isEqualTo(30_000L);
        assertThat(preview.maxMonthlyInstalmentCents()).isEqualTo(15_000L);
        assertThat(preview.suggestedAmountCents()).isEqualTo(120_000L);
        assertThat(preview.suggestedTermMonths()).isEqualTo(8);
    }

    @Test
    void previewForAMemberWhoIsNotEligibleYetHasNoScheduleAndNoSuggestion() {
        ApplicationPreviewView preview = service.previewApplication(UUID.randomUUID(),
            new PreviewApplicationCommand(120_000L, 8, 15, OptionalLong.empty()));

        assertThat(preview.valid()).isFalse();
        assertThat(preview.outcome()).isEqualTo("NOT_ELIGIBLE");
        assertThat(preview.instalments()).isEmpty();
        assertThat(preview.suggestedAmountCents()).isNull();
        assertThat(preview.suggestedTermMonths()).isNull();
        assertThat(preview.reasons()).isNotEmpty();
    }

    static class StubSavingsFacade implements SavingsFacade {
        final Map<String, MemberSavingsSummary> summaries = new HashMap<>();

        @Override
        public void recordDepositBySubscription(String subscriptionId, Money amount, String payPalCaptureId) {}

        @Override
        public void pauseGoalBySubscription(String subscriptionId) {}

        @Override
        public MemberSavingsSummary getMemberSavingsSummary(String memberId) {
            return summaries.getOrDefault(memberId, new MemberSavingsSummary(memberId, 0, 0, 0, 0L, 0L, false));
        }
    }

    static class RecordingLendingFacade implements LendingFacade {
        final List<RegisterDisbursedLoan> registeredLoans = new ArrayList<>();

        @Override
        public LoanSummary registerDisbursedLoan(RegisterDisbursedLoan command) {
            registeredLoans.add(command);
            return new LoanSummary(
                Ids.newId(),
                command.memberId(),
                command.principalCents(),
                command.principalCents(),
                "ACTIVE",
                "MANUAL",
                "CURRENT",
                0,
                1
            );
        }

        @Override
        public Optional<LoanSummary> loan(UUID loanId) { return Optional.empty(); }

        @Override
        public ScheduleView currentSchedule(UUID loanId) { return null; }

        @Override
        public ReschedulePreview previewReschedule(RescheduleRequest request) { return null; }

        @Override
        public ScheduleView applyReschedule(RescheduleRequest request, String approvalRef) { return null; }

        @Override
        public ScheduleView applyReschedule(RescheduleRequest request, String approvalRef, List<Slot> expectedProposed) { return null; }

        @Override
        public void setHardshipOpen(UUID loanId, String caseRef, boolean open) {}

        @Override
        public void placeHold(UUID loanId, int seq, LocalDate until, String caseRef, String cause) {}

        @Override
        public void releaseHold(String caseRef, String reason) {}

        @Override
        public Optional<RiskFacts> riskFacts(UUID loanId, LocalDate asOf) { return Optional.empty(); }

        @Override
        public List<UUID> hardshipScanCandidates(LocalDate asOf, int leadDays, int graceDays, int shardGroup, int shardCount, UUID after, int limit) { return List.of(); }

        @Override
        public EligibilityView eligibilityOf(UUID memberId) { return null; }

        @Override
        public List<MemberLoanView> loansOfMember(UUID memberId) { return List.of(); }

        @Override
        public List<MemberApplicationView> applicationsOfMember(UUID memberId) { return List.of(); }

        @Override
        public CoverResult applyReserveCover(ReserveCover cover) { return null; }
    }
}
