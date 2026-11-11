package com.paypalseed.deskquery.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paypalseed.deskquery.api.DeskAnalystNarrator;
import com.paypalseed.deskquery.api.DeskAnalystNarrator.DeskBriefingInput;
import com.paypalseed.deskquery.api.DeskAnalystNarrator.DeskBriefingResult;
import com.paypalseed.deskquery.application.usecases.GetDeskBriefingUseCase.DeskBriefingResponse;
import com.paypalseed.deskquery.domain.model.DeskBriefingFigures;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.reserve.api.ReserveFacade;
import com.paypalseed.reserve.api.dto.ReserveDtos.ReserveSummary;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GetDeskBriefingUseCaseTest {

    private DeskAnalystNarrator narrator;
    private PortfolioQueries portfolio;
    private ReserveFacade reserve;
    private BusinessClock clock;
    private GetDeskBriefingUseCase useCase;

    @BeforeEach
    void setUp() {
        narrator = mock(DeskAnalystNarrator.class);
        portfolio = mock(PortfolioQueries.class);
        reserve = mock(ReserveFacade.class);
        clock = mock(BusinessClock.class);
        when(clock.today()).thenReturn(LocalDate.of(2026, 11, 2));
        useCase = new GetDeskBriefingUseCase(narrator, portfolio, reserve, clock);
    }

    private void portfolioIs(DeskBriefingFigures figures) {
        when(portfolio.briefingFigures()).thenReturn(figures);
    }

    private void fundIs(long balanceCents, long openExposureCents, Long coverageBps) {
        when(reserve.summary()).thenReturn(new ReserveSummary(balanceCents, balanceCents, 1, 0, 0, openExposureCents,
                coverageBps, List.of(), LocalDate.of(2026, 11, 2)));
    }

    @Test
    void returnsNarratedBriefingWhenNarratorAvailable() {
        portfolioIs(new DeskBriefingFigures(22, 6_500_000L, 17, 5, 3, 48, 1));
        fundIs(1_250_000L, 610_000L, 20_491L);
        when(narrator.brief(any())).thenReturn(new DeskBriefingResult(
            "Executive: 22 loans, 17 healthy.",
            List.of("Priority 1: Hardship cases."),
            List.of("Observation 1: reserve cover."),
            "Focus: Morning reviews.",
            true
        ));

        DeskBriefingResponse response = useCase.getBriefing();

        assertThat(response.businessDate()).isEqualTo("2026-11-02");
        assertThat(response.activeLoansCount()).isEqualTo(22);
        assertThat(response.executiveSummary()).isEqualTo("Executive: 22 loans, 17 healthy.");
        assertThat(response.queuePriorities()).containsExactly("Priority 1: Hardship cases.");
        assertThat(response.modelGenerated()).isTrue();
    }

    @Test
    void everyFigureComesFromTheReadModelsAndGoesToTheNarratorUnchanged() {
        portfolioIs(new DeskBriefingFigures(22, 6_500_000L, 17, 5, 3, 48, 1));
        fundIs(1_250_000L, 610_000L, 20_491L);
        when(narrator.brief(any())).thenReturn(new DeskBriefingResult("s", List.of("p"), List.of("o"), "f", true));

        DeskBriefingResponse response = useCase.getBriefing();

        ArgumentCaptor<DeskBriefingInput> sent = forClass(DeskBriefingInput.class);
        verify(narrator).brief(sent.capture());
        DeskBriefingInput input = sent.getValue();
        assertThat(input.activeLoansCount()).isEqualTo(22).isEqualTo(response.activeLoansCount());
        assertThat(input.totalOutstandingCents()).isEqualTo(6_500_000L).isEqualTo(response.totalOutstandingCents());
        assertThat(input.healthyLoansCount()).isEqualTo(17);
        assertThat(input.atRiskLoansCount()).isEqualTo(5);
        assertThat(input.casesPendingReviewCount()).isEqualTo(3);
        assertThat(input.oldestCaseHours()).isEqualTo(48);
        assertThat(input.slaBreachesCount()).isEqualTo(1);
        assertThat(input.reserveBalanceCents()).isEqualTo(1_250_000L).isEqualTo(response.reserveBalanceCents());
        assertThat(input.reserveCoverageRatio()).isEqualTo(2.0491);
        assertThat(response.reserveCoverageRatio()).isEqualTo(2.0491);
        assertThat(input.templateExecutiveSummary()).isEqualTo(
            "Portfolio holds 22 active loans ($65,000 outstanding) with 17 healthy loans and 5 marked at risk.");
        assertThat(input.templateQueuePriorities()).containsExactly(
            "Review 3 open hardship cases before close of business.",
            "1 queue item past its service deadline: clear these first.");
        assertThat(input.templateRiskObservations()).containsExactly(
            "Healthy loans are 77% of the active portfolio.",
            "Community reserve ($12,500) covers open exposure 2.05x.");
        assertThat(input.templateStaffingFocus()).isEqualTo("Focus primary officer capacity on 3 open hardship cases.");
    }

    @Test
    void twoPortfoliosGiveTwoDifferentBriefings() {
        GetDeskBriefingUseCase plain = new GetDeskBriefingUseCase(null, portfolio, reserve, clock);

        portfolioIs(new DeskBriefingFigures(22, 6_500_000L, 17, 5, 3, 48, 1));
        fundIs(1_250_000L, 610_000L, 20_491L);
        DeskBriefingResponse busy = plain.getBriefing();

        portfolioIs(new DeskBriefingFigures(4, 120_050L, 4, 0, 0, 0, 0));
        fundIs(300_000L, 0L, null);
        DeskBriefingResponse quiet = plain.getBriefing();

        assertThat(busy.activeLoansCount()).isNotEqualTo(quiet.activeLoansCount());
        assertThat(busy.totalOutstandingCents()).isNotEqualTo(quiet.totalOutstandingCents());
        assertThat(busy.executiveSummary()).isNotEqualTo(quiet.executiveSummary());
        assertThat(quiet.executiveSummary())
            .isEqualTo("Portfolio holds 4 active loans ($1,200.50 outstanding) with 4 healthy loans and 0 marked at risk.");
        assertThat(quiet.queuePriorities()).containsExactly("No hardship cases are open.", "No SLA breaches today.");
        assertThat(quiet.riskObservations()).containsExactly("Healthy loans are 100% of the active portfolio.",
            "Community reserve holds $3,000; no loan needs cover right now.");
        assertThat(quiet.reserveCoverageRatio()).isNull();
    }

    @Test
    void returnsTemplateBriefingWhenNarratorIsNull() {
        portfolioIs(new DeskBriefingFigures(22, 6_500_000L, 17, 5, 3, 48, 0));
        fundIs(1_250_000L, 610_000L, 20_491L);
        GetDeskBriefingUseCase noNarratorUseCase = new GetDeskBriefingUseCase(null, portfolio, reserve, clock);

        DeskBriefingResponse response = noNarratorUseCase.getBriefing();

        assertThat(response.businessDate()).isEqualTo("2026-11-02");
        assertThat(response.modelGenerated()).isFalse();
        assertThat(response.executiveSummary()).contains("22 active loans").contains("$65,000 outstanding");
        assertThat(response.queuePriorities()).contains("Oldest open case is 48 hours old; no SLA breaches today.");
    }

    @Test
    void anEmptyPortfolioReadsAsEmptyNotAsDemoData() {
        portfolioIs(new DeskBriefingFigures(0, 0, 0, 0, 0, 0, 0));
        fundIs(0, 0, null);
        GetDeskBriefingUseCase noNarratorUseCase = new GetDeskBriefingUseCase(null, portfolio, reserve, clock);

        DeskBriefingResponse response = noNarratorUseCase.getBriefing();

        assertThat(response.activeLoansCount()).isZero();
        assertThat(response.totalOutstandingCents()).isZero();
        assertThat(response.executiveSummary()).isEqualTo("The portfolio has no active loans today.");
        assertThat(response.riskObservations()).first().isEqualTo("There are no active loans to assess.");
    }
}
