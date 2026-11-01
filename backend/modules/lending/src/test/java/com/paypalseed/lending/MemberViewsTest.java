package com.paypalseed.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paypalseed.engines.schedule.Bucket;
import com.paypalseed.engines.schedule.RepaymentPolicy;
import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.InstalmentLine;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberLoanView;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.lending.application.usecases.LoanQueries;
import com.paypalseed.lending.application.usecases.MemberViews;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.CollectionMode;
import com.paypalseed.lending.domain.model.LoanRow.LoanStatus;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What one member may see of their lending: only their own rows, in the shape the Home page needs. */
class MemberViewsTest {

    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTIVE_LOAN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OLD_LOAN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    private LoanRepository loans;
    private LoanQueries queries;
    private MicroloanApplicationService applications;
    private MemberViews views;

    @BeforeEach
    void setUp() {
        loans = mock(LoanRepository.class);
        queries = mock(LoanQueries.class);
        applications = mock(MicroloanApplicationService.class);
        views = new MemberViews(loans, queries, applications);
    }

    private static LoanRow loan(UUID id, LoanStatus status, CollectionMode mode, LocalDate disbursedOn, long outstanding) {
        return new LoanRow(id, MEMBER, 120_000, 8, 15, disbursedOn, RepaymentPolicy.V1.version(), status, mode, 1,
            outstanding, 0, 12, Bucket.DPD_8_30, null, null, null, 1);
    }

    private static LoanApplicationView application(String status, Instant createdAt, String payoutIssue) {
        return new LoanApplicationView(UUID.randomUUID(), MEMBER, "Stand mixer", 120_000, 8, 15, "PAYPAL", true, "LEVEL_1",
            15_000, 15_000, status, UUID.randomUUID(), UUID.randomUUID(), "private note", null, null,
            createdAt.plusSeconds(172_800), List.of("a sizing reason"), createdAt, createdAt, payoutIssue);
    }

    @Test
    void anActiveLoanShowsItsOldestOwedInstalmentAndWhetherAutopayCollectsIt() {
        LoanRow active = loan(ACTIVE_LOAN, LoanStatus.ACTIVE, CollectionMode.AUTOPAY, LocalDate.of(2026, 9, 1), 90_000);
        when(loans.byMember(MEMBER)).thenReturn(List.of(active));
        when(queries.schedule(active, Optional.empty())).thenReturn(new ScheduleView(ACTIVE_LOAN, 1, "ORIGINAL",
            LocalDate.of(2026, 11, 10), 120_000, 90_000, 0, "ACTIVE", "AUTOPAY", 12, "DPD_8_30", List.of(
                new InstalmentLine(1, LocalDate.of(2026, 10, 15), 15_000, 15_000, 0, "PAID", LocalDate.of(2026, 10, 15)),
                new InstalmentLine(2, LocalDate.of(2026, 11, 15), 15_000, 5_000, 10_000, "PARTIAL", null),
                new InstalmentLine(3, LocalDate.of(2026, 12, 15), 15_000, 0, 15_000, "DUE", null))));

        List<MemberLoanView> result = views.loans(MEMBER);

        assertThat(result).singleElement().satisfies(l -> {
            assertThat(l.loanId()).isEqualTo(ACTIVE_LOAN);
            assertThat(l.status()).isEqualTo("ACTIVE");
            assertThat(l.principalCents()).isEqualTo(120_000);
            assertThat(l.outstandingCents()).isEqualTo(90_000);
            assertThat(l.autopay()).isTrue();
            assertThat(l.nextDue().seq()).isEqualTo(2);
            assertThat(l.nextDue().dueDate()).isEqualTo(LocalDate.of(2026, 11, 15));
            assertThat(l.nextDue().amountCents()).isEqualTo(15_000);
            assertThat(l.nextDue().remainingCents()).isEqualTo(10_000);
        });
        assertThat(result.toString()).as("no days past due and no bucket for the member").doesNotContain("DPD").doesNotContain("dpd");
    }

    @Test
    void loansComeNewestFirstAndAFinishedLoanHasNoNextDueAndIsNotReadForItsSchedule() {
        LoanRow active = loan(ACTIVE_LOAN, LoanStatus.ACTIVE, CollectionMode.MANUAL, LocalDate.of(2026, 9, 1), 90_000);
        LoanRow repaid = loan(OLD_LOAN, LoanStatus.REPAID, CollectionMode.MANUAL, LocalDate.of(2025, 9, 1), 0);
        when(loans.byMember(MEMBER)).thenReturn(List.of(repaid, active));
        when(queries.schedule(active, Optional.empty())).thenReturn(new ScheduleView(ACTIVE_LOAN, 1, "ORIGINAL",
            LocalDate.of(2026, 11, 10), 120_000, 90_000, 0, "ACTIVE", "MANUAL", 0, "CURRENT", List.of()));

        List<MemberLoanView> result = views.loans(MEMBER);

        assertThat(result).extracting(MemberLoanView::loanId).containsExactly(ACTIVE_LOAN, OLD_LOAN);
        assertThat(result.get(0).nextDue()).as("nothing owed in the schedule").isNull();
        assertThat(result.get(0).autopay()).isFalse();
        assertThat(result.get(1).status()).isEqualTo("REPAID");
        assertThat(result.get(1).nextDue()).isNull();
        verify(queries, never()).schedule(repaid, Optional.empty());
    }

    @Test
    void onlyTheNamedMembersLoansAreAsked() {
        when(loans.byMember(OTHER)).thenReturn(List.of());

        assertThat(views.loans(OTHER)).isEmpty();

        verify(loans).byMember(OTHER);
        verify(loans, never()).byMember(MEMBER);
    }

    @Test
    void applicationsComeNewestFirstWithoutOfficersReasonsOrNotes() {
        Instant older = Instant.parse("2026-10-01T10:00:00Z");
        Instant newer = Instant.parse("2026-11-01T10:00:00Z");
        when(applications.listMemberApplications(MEMBER)).thenReturn(List.of(
            application("REJECTED", older, null), application("APPROVED", newer, "RECEIVER_UNREGISTERED")));

        List<MemberApplicationView> result = views.applications(MEMBER);

        assertThat(result).extracting(MemberApplicationView::status).containsExactly("APPROVED", "REJECTED");
        MemberApplicationView first = result.get(0);
        assertThat(first.requestedAmountCents()).isEqualTo(120_000);
        assertThat(first.termMonths()).isEqualTo(8);
        assertThat(first.payoutIssue()).isEqualTo("RECEIVER_UNREGISTERED");
        assertThat(first.coolingOffExpiresAt()).isEqualTo(newer.plusSeconds(172_800));
        assertThat(result.toString()).doesNotContain("private note").doesNotContain("sizing reason");
    }

    @Test
    void eligibilityIsLendingsOwnAnswerForThisMember() {
        EligibilityView eligibility = new EligibilityView(MEMBER, true, "LEVEL_1", 1, 150_000, 120_000, 15_000, 3, 18, 4, 7_500,
            false, 0, List.of("ok"));
        when(applications.checkEligibility(MEMBER, OptionalLong.empty())).thenReturn(eligibility);

        assertThat(views.eligibility(MEMBER)).isSameAs(eligibility);
    }
}
