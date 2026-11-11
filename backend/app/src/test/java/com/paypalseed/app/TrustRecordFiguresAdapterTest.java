package com.paypalseed.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paypalseed.creditrecord.api.TrustRecordFigures.MemberFigures;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.InstalmentLine;
import com.paypalseed.lending.api.dto.LendingDtos.MemberLoanView;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrustRecordFiguresAdapterTest {

    private static final UUID ANA = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");

    private SavingsFacade savings;
    private LendingFacade lending;
    private TrustRecordFiguresAdapter adapter;

    @BeforeEach
    void setUp() {
        savings = mock(SavingsFacade.class);
        lending = mock(LendingFacade.class);
        adapter = new TrustRecordFiguresAdapter(savings, lending);
    }

    private static InstalmentLine line(int seq, long paid, long fromFund) {
        return new InstalmentLine(seq, LocalDate.of(2026, 10, seq), 10_000, paid, 10_000 - paid, "PAID", null, fromFund);
    }

    private static ScheduleView schedule(UUID loan, InstalmentLine... lines) {
        return new ScheduleView(loan, 1, "INITIAL", LocalDate.of(2026, 11, 9), 30_000, 0, 0, "ACTIVE", "AUTOPAY", 0,
                "CURRENT", List.of(lines));
    }

    private static MemberLoanView loan(UUID id, String status) {
        return new MemberLoanView(id, status, 30_000, 10_000, null, false);
    }

    @Test
    void streakSavedAndRepaidComeFromThatMembersOwnFacades() {
        UUID loan = UUID.randomUUID();
        when(savings.getMemberSavingsSummary(ANA.toString())).thenReturn(new MemberSavingsSummary(ANA.toString(), 5, 5, 0,
                7_500, 37_500, true));
        when(lending.loansOfMember(ANA)).thenReturn(List.of(loan(loan, "ACTIVE")));
        when(lending.currentSchedule(loan)).thenReturn(schedule(loan, line(1, 10_000, 0), line(2, 10_000, 0), line(3, 0, 0)));

        assertThat(adapter.of(ANA)).isEqualTo(new MemberFigures(5, 37_500, 20_000));
    }

    @Test
    void whatTheSafetyFundPaidIsNotTheMembersRepayment() {
        UUID loan = UUID.randomUUID();
        when(savings.getMemberSavingsSummary(ANA.toString())).thenReturn(new MemberSavingsSummary(ANA.toString(), 0, 0, 0, 0, 0,
                false));
        when(lending.loansOfMember(ANA)).thenReturn(List.of(loan(loan, "ACTIVE")));
        when(lending.currentSchedule(loan)).thenReturn(schedule(loan, line(1, 10_000, 10_000), line(2, 10_000, 4_000)));

        assertThat(adapter.of(ANA).totalRepaidCents()).isEqualTo(6_000);
    }

    @Test
    void loansThatNeverStoodAddNothing() {
        UUID cancelled = UUID.randomUUID();
        UUID returned = UUID.randomUUID();
        when(savings.getMemberSavingsSummary(ANA.toString())).thenReturn(noSavings(ANA));
        when(lending.loansOfMember(ANA)).thenReturn(List.of(loan(cancelled, "CANCELLED"), loan(returned, "DISBURSEMENT_RETURNED")));

        assertThat(adapter.of(ANA).totalRepaidCents()).isZero();
        verify(lending, never()).currentSchedule(any());
    }

    @Test
    void anotherMembersFiguresAreNeverMixedIn() {
        UUID anasLoan = UUID.randomUUID();
        when(savings.getMemberSavingsSummary(ANA.toString())).thenReturn(new MemberSavingsSummary(ANA.toString(), 5, 5, 0,
                7_500, 37_500, true));
        when(lending.loansOfMember(ANA)).thenReturn(List.of(loan(anasLoan, "ACTIVE")));
        when(lending.currentSchedule(anasLoan)).thenReturn(schedule(anasLoan, line(1, 10_000, 0)));
        when(savings.getMemberSavingsSummary(BOB.toString())).thenReturn(noSavings(BOB));
        when(lending.loansOfMember(BOB)).thenReturn(List.of());

        assertThat(adapter.of(BOB)).isEqualTo(MemberFigures.NONE);
        assertThat(adapter.of(ANA)).isNotEqualTo(adapter.of(BOB));
    }

    private static MemberSavingsSummary noSavings(UUID member) {
        return new MemberSavingsSummary(member.toString(), 0, 0, 0, 0, 0, false);
    }
}
