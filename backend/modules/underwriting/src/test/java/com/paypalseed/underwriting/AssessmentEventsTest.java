package com.paypalseed.underwriting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.FairCheckCardView;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.api.events.AssessmentChanged;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService.RunFairCheckCommand;
import com.paypalseed.underwriting.application.usecases.IncomeConsentSync;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment.OfficerDecisionType;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Consent;
import com.paypalseed.underwriting.infrastructure.persistence.UnderwritingRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** Every assessment state change publishes a whole-state snapshot; consent withdrawal is idempotent. */
class AssessmentEventsTest {

    private static final UUID ANA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OFFICER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final List<Object> published = new ArrayList<>();
    private FairLoanCheckService service;

    @BeforeEach
    void setUp() {
        SavingsFacade savings = new SavingsFacade() {
            @Override
            public void recordDepositBySubscription(String subscriptionId, Money amount, String payPalCaptureId) {
            }

            @Override
            public void pauseGoalBySubscription(String subscriptionId) {
            }

            @Override
            public MemberSavingsSummary getMemberSavingsSummary(String memberId) {
                return new MemberSavingsSummary(memberId, 4, 4, 0, 7_500L, 30_000L, true);
            }
        };
        ApplicationEventPublisher events = published::add;
        service = new FairLoanCheckService(new UnderwritingRepository(), savings, null, null, events, null);
        service.grantConsent(ANA);
    }

    private FairCheckCardView create() {
        return service.runFairLoanCheck(ANA, new RunFairCheckCommand(UUID.randomUUID(), "Commercial stand mixer", 120_000L, 8,
                List.of(new IncomeMonthDto("2026-10", 160_000L)), 160_000L, 80_000L, 0, false));
    }

    private AssessmentChanged last() {
        return (AssessmentChanged) published.get(published.size() - 1);
    }

    @Test
    void creationPublishesAPendingSnapshotWithTheCreationTime() {
        FairCheckCardView card = create();

        assertThat(published).hasSize(1);
        AssessmentChanged event = last();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.assessmentId()).isEqualTo(card.assessmentId());
        assertThat(event.memberId()).isEqualTo(ANA);
        assertThat(event.applicationId()).isEqualTo(card.applicationId());
        assertThat(event.recommended()).isEqualTo(card.recommended());
        assertThat(event.recommendedAmountCents()).isEqualTo(card.suggestedLoanCents());
        assertThat(event.officerDecision()).isEqualTo("PENDING");
        assertThat(event.memberDisputed()).isFalse();
        assertThat(event.createdAt()).isEqualTo(card.createdAt());
        assertThat(event.changedAt()).isEqualTo(card.createdAt());
    }

    @Test
    void aDisputePublishesTheSameSnapshotMarkedDisputedAndKeepsTheCreationTime() {
        FairCheckCardView card = create();

        service.disputeAssessment(card.assessmentId(), ANA, "July was a closure.");

        assertThat(published).hasSize(2);
        AssessmentChanged event = last();
        assertThat(event.memberDisputed()).isTrue();
        assertThat(event.officerDecision()).isEqualTo("PENDING");
        assertThat(event.changedAt()).isEqualTo(card.createdAt());
    }

    @Test
    void approvalAndRejectionPublishTheDecisionAndItsTime() {
        FairCheckCardView approved = create();
        service.recordOfficerDecision(approved.assessmentId(), OFFICER, OfficerDecisionType.APPROVED, null, null, "Verified.");
        assertThat(last().officerDecision()).isEqualTo("APPROVED");
        assertThat(last().changedAt()).isAfterOrEqualTo(last().createdAt());

        FairCheckCardView declined = create();
        service.recordOfficerDecision(declined.assessmentId(), OFFICER, OfficerDecisionType.DECLINED, null, null, "Not now.");
        assertThat(last().assessmentId()).isEqualTo(declined.assessmentId());
        assertThat(last().officerDecision()).isEqualTo("DECLINED");
        assertThat(published).hasSize(4);
    }

    @Test
    void theEventCarriesNoFreeTextFromAMemberOrAnOfficer() {
        FairCheckCardView card = create();
        service.disputeAssessment(card.assessmentId(), ANA, "my secret note about July");
        service.recordOfficerDecision(card.assessmentId(), OFFICER, OfficerDecisionType.APPROVED, null, null, "officer secret reason");

        assertThat(published).allSatisfy(e -> assertThat(e.toString())
                .doesNotContain("secret").doesNotContain("stand mixer"));
    }

    @Test
    void nothingIsPublishedWhenTheCheckIsRefusedForWantOfConsent() {
        UUID stranger = UUID.randomUUID();
        try {
            service.runFairLoanCheck(stranger, new RunFairCheckCommand(null, "x", 100_000L, 6, List.of(), 100_000L, 0L, 0, false));
        } catch (RuntimeException expected) {
            // consent-required
        }
        assertThat(published).isEmpty();
    }

    @Test
    void withdrawingIncomeReadClosesTheEnforcingConsentAndForgetsTheWeeksOnce() {
        IncomeRepository income = mock(IncomeRepository.class);
        FakeBusinessClock clock = new FakeBusinessClock(LocalDate.of(2026, 11, 10));
        IncomeConsentSync sync = new IncomeConsentSync(income, clock);

        when(income.consent(ANA)).thenReturn(Optional.of(new Consent(true, null)));
        sync.incomeReadWithdrawn(ANA);
        verify(income).revoke(ANA, clock.now());
        verify(income).purgeFetched(ANA);
    }

    @Test
    void withdrawingTwiceOrWithoutAnyConsentCreatesAndChangesNothing() {
        IncomeRepository income = mock(IncomeRepository.class);
        IncomeConsentSync sync = new IncomeConsentSync(income, new FakeBusinessClock(LocalDate.of(2026, 11, 10)));

        when(income.consent(ANA)).thenReturn(Optional.of(new Consent(false, null)));
        sync.incomeReadWithdrawn(ANA);
        when(income.consent(ANA)).thenReturn(Optional.empty());
        sync.incomeReadWithdrawn(ANA);

        verify(income, never()).revoke(any(), any());
    }
}
