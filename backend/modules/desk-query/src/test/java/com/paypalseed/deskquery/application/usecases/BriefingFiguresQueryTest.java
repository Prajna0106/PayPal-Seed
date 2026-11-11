package com.paypalseed.deskquery.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paypalseed.deskquery.DbFixture;
import com.paypalseed.deskquery.domain.model.DeskBriefingFigures;
import com.paypalseed.deskquery.domain.model.NextDue.Instalment;
import com.paypalseed.deskquery.infrastructure.persistence.ApprovalViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.HardshipViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.LoanViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.SummaryRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The morning briefing's figures against a real database: each one is read from the projections, none is fixed. */
class BriefingFiguresQueryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    private static final Instant NOW = Instant.parse("2026-11-09T09:00:00Z");

    private static LoanViewRepository loans;
    private static HardshipViewRepository hardship;
    private static ApprovalViewRepository approvals;
    private static PortfolioQueries portfolio;

    @BeforeAll
    static void connect() {
        loans = new LoanViewRepository(DbFixture.dsl());
        hardship = new HardshipViewRepository(DbFixture.dsl());
        approvals = new ApprovalViewRepository(DbFixture.dsl());
        BusinessClock clock = mock(BusinessClock.class);
        when(clock.today()).thenReturn(TODAY);
        when(clock.now()).thenReturn(NOW);
        portfolio = new PortfolioQueries(new SummaryRepository(DbFixture.dsl()),
                new DeskQuerySettings(Duration.ofHours(24), Duration.ofHours(4), 50, 200, 10_000, ""), clock);
    }

    @BeforeEach
    void clean() {
        DbFixture.clean();
    }

    private static UUID loan(int n) {
        return new UUID(1, n);
    }

    private static void activeLoan(int n, long outstanding, String bucket, int daysLate) {
        loans.ensure(loan(n), new UUID(2, n));
        loans.initialise(loan(n), outstanding, LocalDate.of(2026, 9, 1));
        loans.applySchedule(loan(n), 1, List.of(new Instalment(1, LocalDate.of(2026, 12, 1), outstanding)));
        loans.recordMoney(loan(n), TODAY, outstanding, "ACTIVE");
        loans.recordDelinquency(loan(n), TODAY, bucket, daysLate);
    }

    @Test
    void anEmptyDatabaseBriefsAsNothingOnTheBooks() {
        assertThat(portfolio.briefingFigures()).isEqualTo(new DeskBriefingFigures(0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void loansHealthAndOutstandingComeFromTheLoanProjection() {
        activeLoan(1, 100_000, "CURRENT", 0);
        activeLoan(2, 250_000, "CURRENT", 0);
        activeLoan(3, 80_000, "DPD_8_30", 12);
        activeLoan(4, 60_000, "DPD_60_PLUS", 70);
        // Repaid and cancelled loans owe nothing and are not on the books as active.
        activeLoan(5, 40_000, "CURRENT", 0);
        loans.recordMoney(loan(5), TODAY.plusDays(1), 0, "REPAID");
        activeLoan(6, 40_000, "CURRENT", 0);
        loans.recordVoided(loan(6), TODAY.plusDays(1), "CANCELLED");

        DeskBriefingFigures figures = portfolio.briefingFigures();

        assertThat(figures.activeLoans()).isEqualTo(4);
        assertThat(figures.healthyLoans()).isEqualTo(2);
        assertThat(figures.atRiskLoans()).isEqualTo(2);
        assertThat(figures.outstandingCents()).isEqualTo(490_000);
    }

    @Test
    void openCasesTheirAgeAndOverdueItemsComeFromTheHardshipAndApprovalProjections() {
        hardship.opened(UUID.randomUUID(), loan(1), new UUID(2, 1), "LIKELY_MISS", "PROACTIVE_SCAN", LocalDate.of(2026, 11, 7));
        hardship.opened(UUID.randomUUID(), loan(2), new UUID(2, 2), "WATCH", "MEMBER_HELP", LocalDate.of(2026, 11, 8));
        UUID closed = UUID.randomUUID();
        hardship.opened(closed, loan(3), new UUID(2, 3), "WATCH", "MEMBER_HELP", LocalDate.of(2026, 10, 1));
        hardship.closed(closed, loan(3), new UUID(2, 3), LocalDate.of(2026, 10, 5), "APPLIED");
        // One decision is past its deadline, one is not.
        approvals.requested(UUID.randomUUID(), UUID.randomUUID(), "TIER_1", NOW.minus(Duration.ofHours(2)));
        approvals.requested(UUID.randomUUID(), UUID.randomUUID(), "TIER_1", NOW.plus(Duration.ofHours(20)));

        DeskBriefingFigures figures = portfolio.briefingFigures();

        assertThat(figures.openHardshipCases()).isEqualTo(2);
        assertThat(figures.oldestOpenCaseHours()).as("the closed case from October does not count").isEqualTo(48);
        assertThat(figures.slaBreaches()).isEqualTo(1);
    }
}
