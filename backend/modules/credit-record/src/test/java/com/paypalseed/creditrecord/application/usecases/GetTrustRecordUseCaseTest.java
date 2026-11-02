package com.paypalseed.creditrecord.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paypalseed.creditrecord.api.TrustRecordNarrator;
import com.paypalseed.creditrecord.api.TrustRecordNarrator.TrustNarrationResult;
import com.paypalseed.creditrecord.application.usecases.GetTrustRecordUseCase.TrustRecordResponse;
import com.paypalseed.platform.kernel.BusinessClock;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetTrustRecordUseCaseTest {

    private TrustRecordNarrator narrator;
    private BusinessClock clock;
    private GetTrustRecordUseCase useCase;

    @BeforeEach
    void setUp() {
        narrator = mock(TrustRecordNarrator.class);
        clock = mock(BusinessClock.class);
        when(clock.today()).thenReturn(LocalDate.of(2026, 11, 2));
        useCase = new GetTrustRecordUseCase(narrator, clock);
    }

    @Test
    void returnsNarratedTrustRecordWithVerificationHash() {
        when(narrator.narrate(any())).thenReturn(new TrustNarrationResult(
            "Summary EN: Level 1 member with 8 on-time payments.",
            "Summary ES: Miembro Nivel 1 con 8 pagos a tiempo.",
            "Attestation: Verified community record.",
            "Advice: Complete loan for Level 2.",
            true
        ));

        UUID memberId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        TrustRecordResponse response = useCase.getTrustRecord(memberId);

        assertThat(response.memberId()).isEqualTo(memberId);
        assertThat(response.currentTier()).isEqualTo("LEVEL_1");
        assertThat(response.currentLimitCents()).isEqualTo(120000L);
        assertThat(response.summaryEn()).contains("Level 1");
        assertThat(response.verificationHash()).isNotBlank().hasSize(64);
        assertThat(response.modelGenerated()).isTrue();
    }

    @Test
    void returnsTemplateTrustRecordWhenNarratorIsNull() {
        GetTrustRecordUseCase noNarratorUseCase = new GetTrustRecordUseCase(null, clock);

        UUID memberId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        TrustRecordResponse response = noNarratorUseCase.getTrustRecord(memberId);

        assertThat(response.memberId()).isEqualTo(memberId);
        assertThat(response.modelGenerated()).isFalse();
        assertThat(response.summaryEn()).contains("Level 1 with 8 on-time repayments");
        assertThat(response.verificationHash()).isNotBlank();
    }
}
