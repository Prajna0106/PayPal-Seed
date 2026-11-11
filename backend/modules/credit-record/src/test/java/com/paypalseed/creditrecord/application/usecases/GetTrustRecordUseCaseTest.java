package com.paypalseed.creditrecord.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paypalseed.creditrecord.api.TrustRecordFigures;
import com.paypalseed.creditrecord.api.TrustRecordFigures.MemberFigures;
import com.paypalseed.creditrecord.api.TrustRecordNarrator;
import com.paypalseed.creditrecord.api.TrustRecordNarrator.TrustNarrationInput;
import com.paypalseed.creditrecord.api.TrustRecordNarrator.TrustNarrationResult;
import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.creditrecord.application.usecases.GetTrustRecordUseCase.TrustRecordResponse;
import com.paypalseed.creditrecord.support.InMemoryStores;
import com.paypalseed.platform.kernel.BusinessClock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class GetTrustRecordUseCaseTest {

    private static final UUID STEADY_MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID NEW_MEMBER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private TrustRecordNarrator narrator;
    private BusinessClock clock;
    private InMemoryStores.Records store;
    private RecordIngest ingest;
    private RecordQueries queries;
    private final Map<UUID, MemberFigures> money = new HashMap<>();
    private final TrustRecordFigures figures = id -> money.getOrDefault(id, MemberFigures.NONE);
    private GetTrustRecordUseCase useCase;

    @BeforeEach
    void setUp() {
        narrator = mock(TrustRecordNarrator.class);
        clock = mock(BusinessClock.class);
        when(clock.today()).thenReturn(LocalDate.of(2026, 11, 2));
        store = new InMemoryStores.Records();
        ingest = new RecordIngest(store, mock(ApplicationEventPublisher.class));
        queries = new RecordQueries(store);
        useCase = new GetTrustRecordUseCase(narrator, queries, figures, clock);

        // One member with a record: 7 months of saving (BUILDING needs 3, STEADY needs 6 and a loan repaid on plan) and
        // 3 instalments paid on time; another who has done nothing yet.
        for (int month = 1; month <= 7; month++) {
            ingest.ingest(RecordFact.savingsDeposit("dep-" + month, STEADY_MEMBER, LocalDate.of(2026, month, 10)));
        }
        UUID loan = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
        ingest.ingest(RecordFact.loanDisbursed("loan-disbursed:" + loan, STEADY_MEMBER, loan, LocalDate.of(2026, 3, 1)));
        for (int seq = 1; seq <= 3; seq++) {
            ingest.ingest(RecordFact.instalmentPaid(STEADY_MEMBER, loan, seq, 0, LocalDate.of(2026, 3 + seq, 15)));
        }
        ingest.ingest(RecordFact.loanRepaid(STEADY_MEMBER, loan, 0, LocalDate.of(2026, 6, 15)));
        money.put(STEADY_MEMBER, new MemberFigures(7, 30_000L, 120_000L));
    }

    @Test
    void returnsNarratedTrustRecordWithVerificationHash() {
        when(narrator.narrate(any())).thenReturn(new TrustNarrationResult(
            "Summary EN: Steady member with 3 on-time payments.",
            "Summary ES: Miembro Constante con 3 pagos a tiempo.",
            "Attestation: Verified community record.",
            "Advice: Keep saving for the next level.",
            true
        ));

        TrustRecordResponse response = useCase.getTrustRecord(STEADY_MEMBER);

        assertThat(response.memberId()).isEqualTo(STEADY_MEMBER);
        assertThat(response.currentTier()).isEqualTo("STEADY");
        assertThat(response.currentLimitCents()).isEqualTo(300_000L);
        assertThat(response.nextTier()).isEqualTo("TRUSTED");
        assertThat(response.nextLimitCents()).isEqualTo(500_000L);
        assertThat(response.summaryEn()).contains("Steady");
        assertThat(response.verificationHash()).isNotBlank().hasSize(64);
        assertThat(response.modelGenerated()).isTrue();
    }

    @Test
    void everyFigureIsTheMembersOwnAndGoesToTheNarratorUnchanged() {
        when(narrator.narrate(any())).thenReturn(new TrustNarrationResult("a", "b", "c", "d", true));

        TrustRecordResponse response = useCase.getTrustRecord(STEADY_MEMBER);

        assertThat(response.onTimeRepaymentsCount()).isEqualTo(3);
        assertThat(response.streakMonths()).isEqualTo(7);
        assertThat(response.totalSavedCents()).isEqualTo(30_000L);
        assertThat(response.totalRepaidCents()).isEqualTo(120_000L);

        ArgumentCaptor<TrustNarrationInput> sent = ArgumentCaptor.forClass(TrustNarrationInput.class);
        verify(narrator).narrate(sent.capture());
        TrustNarrationInput input = sent.getValue();
        assertThat(input.memberId()).isEqualTo(STEADY_MEMBER.toString());
        assertThat(input.currentTier()).isEqualTo("STEADY");
        assertThat(input.nextTier()).isEqualTo("TRUSTED");
        assertThat(input.currentLimitCents()).isEqualTo(300_000L);
        assertThat(input.nextLimitCents()).isEqualTo(500_000L);
        assertThat(input.onTimeRepaymentsCount()).isEqualTo(3);
        assertThat(input.streakMonths()).isEqualTo(7);
        assertThat(input.totalSavedCents()).isEqualTo(30_000L);
        assertThat(input.totalRepaidCents()).isEqualTo(120_000L);
        assertThat(input.templateSummaryEn()).isEqualTo("You are at the Steady level of your Seed record, with "
            + "3 on-time repayments, 7 months of saving in a row and $300 saved. The next level, Trusted, unlocks a loan "
            + "limit of $5,000.");
        assertThat(input.templateAttestation()).isEqualTo("Community Credit Standing: level Steady. 3 on-time repayments "
            + "recorded, $1,200 repaid in total, 0 default events on record.");
    }

    @Test
    void returnsTemplateTrustRecordWhenNarratorIsNull() {
        GetTrustRecordUseCase noNarratorUseCase = new GetTrustRecordUseCase(null, queries, figures, clock);

        TrustRecordResponse response = noNarratorUseCase.getTrustRecord(STEADY_MEMBER);

        assertThat(response.memberId()).isEqualTo(STEADY_MEMBER);
        assertThat(response.modelGenerated()).isFalse();
        assertThat(response.summaryEn()).contains("Steady level").contains("3 on-time repayments").contains("$300 saved");
        assertThat(response.summaryEs()).contains("Constante").contains("3 pagos puntuales");
        assertThat(response.milestoneAdvice()).contains("Trusted");
        assertThat(response.verificationHash()).isNotBlank();
    }

    @Test
    void twoMembersGetTwoDifferentRecords() {
        GetTrustRecordUseCase plain = new GetTrustRecordUseCase(null, queries, figures, clock);

        TrustRecordResponse steady = plain.getTrustRecord(STEADY_MEMBER);
        TrustRecordResponse fresh = plain.getTrustRecord(NEW_MEMBER);

        assertThat(fresh.memberId()).isEqualTo(NEW_MEMBER);
        assertThat(fresh.currentTier()).isEqualTo("NEW").isNotEqualTo(steady.currentTier());
        assertThat(fresh.nextTier()).isEqualTo("BUILDING");
        assertThat(fresh.currentLimitCents()).isZero();
        assertThat(fresh.onTimeRepaymentsCount()).isZero();
        assertThat(fresh.streakMonths()).isZero();
        assertThat(fresh.totalSavedCents()).isZero();
        assertThat(fresh.totalRepaidCents()).isZero();
        assertThat(fresh.summaryEn()).startsWith("Your Seed record is just beginning");
        assertThat(fresh.verificationHash()).isNotEqualTo(steady.verificationHash());
        // Nothing of the first member shows in the second member's wording.
        assertThat(fresh.summaryEn()).doesNotContain("Steady").doesNotContain("$300");
        assertThat(fresh.certificateAttestation()).doesNotContain("$1,200");
    }

    @Test
    void theTopLevelHasNoNextTier() {
        UUID top = UUID.fromString("33333333-3333-3333-3333-333333333333");
        for (int month = 1; month <= 12; month++) {
            ingest.ingest(RecordFact.savingsDeposit("top-dep-" + month, top, LocalDate.of(2025, month, 5)));
        }
        for (int n = 1; n <= 3; n++) {
            UUID loan = UUID.fromString("bbbbbbbb-0000-4000-8000-00000000000" + n);
            ingest.ingest(RecordFact.loanDisbursed("top-loan-" + n, top, loan, LocalDate.of(2025, n, 1)));
            ingest.ingest(RecordFact.instalmentPaid(top, loan, 1, 0, LocalDate.of(2025, n + 1, 1)));
            ingest.ingest(RecordFact.loanRepaid(top, loan, 0, LocalDate.of(2025, n + 1, 2)));
        }
        GetTrustRecordUseCase plain = new GetTrustRecordUseCase(null, queries, figures, clock);

        TrustRecordResponse response = plain.getTrustRecord(top);

        assertThat(response.currentTier()).isEqualTo("EXEMPLARY");
        assertThat(response.nextTier()).isNull();
        assertThat(response.nextLimitCents()).isNull();
        assertThat(response.summaryEn()).endsWith("This is the highest level.");
        assertThat(response.milestoneAdvice()).isEqualTo("Keep saving and repaying on time to stay at your level.");
    }
}
