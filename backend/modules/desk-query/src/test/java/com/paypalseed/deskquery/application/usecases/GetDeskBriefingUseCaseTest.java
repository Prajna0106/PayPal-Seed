package com.paypalseed.deskquery.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paypalseed.deskquery.api.DeskAnalystNarrator;
import com.paypalseed.deskquery.api.DeskAnalystNarrator.DeskBriefingResult;
import com.paypalseed.deskquery.application.usecases.GetDeskBriefingUseCase.DeskBriefingResponse;
import com.paypalseed.platform.kernel.BusinessClock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetDeskBriefingUseCaseTest {

    private DeskAnalystNarrator narrator;
    private BusinessClock clock;
    private GetDeskBriefingUseCase useCase;

    @BeforeEach
    void setUp() {
        narrator = mock(DeskAnalystNarrator.class);
        clock = mock(BusinessClock.class);
        when(clock.today()).thenReturn(LocalDate.of(2026, 11, 2));
        useCase = new GetDeskBriefingUseCase(narrator, clock);
    }

    @Test
    void returnsNarratedBriefingWhenNarratorAvailable() {
        when(narrator.brief(any())).thenReturn(new DeskBriefingResult(
            "Executive: 142 loans healthy.",
            List.of("Priority 1: Hardship cases."),
            List.of("Observation 1: 2.05x reserve coverage."),
            "Focus: Morning reviews.",
            true
        ));

        DeskBriefingResponse response = useCase.getBriefing();

        assertThat(response.businessDate()).isEqualTo("2026-11-02");
        assertThat(response.activeLoansCount()).isEqualTo(142);
        assertThat(response.executiveSummary()).isEqualTo("Executive: 142 loans healthy.");
        assertThat(response.queuePriorities()).containsExactly("Priority 1: Hardship cases.");
        assertThat(response.modelGenerated()).isTrue();
    }

    @Test
    void returnsTemplateBriefingWhenNarratorIsNull() {
        GetDeskBriefingUseCase noNarratorUseCase = new GetDeskBriefingUseCase(null, clock);

        DeskBriefingResponse response = noNarratorUseCase.getBriefing();

        assertThat(response.businessDate()).isEqualTo("2026-11-02");
        assertThat(response.modelGenerated()).isFalse();
        assertThat(response.executiveSummary()).contains("142 active loans");
    }
}
