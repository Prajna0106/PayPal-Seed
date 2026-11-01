package com.paypalseed.app.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.paypalseed.hardship.api.HardshipFacade;
import com.paypalseed.hardship.api.dto.HardshipDtos.MemberCaseView;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberLoanView;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberGoalView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The production per-member reads: one member id in, the owning facade's answer out, nothing held back or added. */
class FacadeMemberReadsTest {

    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LOAN = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private SavingsFacade savings;
    private LendingFacade lending;
    private HardshipFacade hardship;
    private FacadeMemberReads reads;

    @BeforeEach
    void setUp() {
        savings = mock(SavingsFacade.class);
        lending = mock(LendingFacade.class);
        hardship = mock(HardshipFacade.class);
        reads = new FacadeMemberReads(savings, lending, hardship);
    }

    @Test
    void goalsAreAskedForByTheMemberIdAndMappedFieldForField() {
        UUID goal = UUID.randomUUID();
        when(savings.goalsOfMember(MEMBER.toString())).thenReturn(List.of(new MemberGoalView(goal, "Stand mixer", "ACTIVE",
            120_000, 7_500, 7_500, "USD", LocalDate.of(2027, 6, 1))));

        assertThat(reads.goals(MEMBER)).containsExactly(new MemberReads.Goal(goal, "Stand mixer", "ACTIVE", 120_000, 7_500,
            7_500, "USD", LocalDate.of(2027, 6, 1)));
    }

    @Test
    void lendingAnswersAreThePassedOnAsGiven() {
        EligibilityView eligibility = new EligibilityView(MEMBER, true, "LEVEL_1", 1, 150_000, 120_000, 15_000, 3, 18, 4, 7_500,
            false, 0, List.of("ok"));
        List<MemberLoanView> loans = List.of(new MemberLoanView(LOAN, "ACTIVE", 120_000, 90_000, null, true));
        List<MemberApplicationView> applications = List.of(
            new MemberApplicationView(UUID.randomUUID(), "SUBMITTED", 120_000, 8, Instant.EPOCH, null));
        when(lending.eligibilityOf(MEMBER)).thenReturn(eligibility);
        when(lending.loansOfMember(MEMBER)).thenReturn(loans);
        when(lending.applicationsOfMember(MEMBER)).thenReturn(applications);

        assertThat(reads.eligibility(MEMBER)).isSameAs(eligibility);
        assertThat(reads.loans(MEMBER)).isSameAs(loans);
        assertThat(reads.applications(MEMBER)).isSameAs(applications);
    }

    @Test
    void theOpenHardshipCaseIsTheMembersOwnSmallView() {
        MemberCaseView view = new MemberCaseView(UUID.randomUUID(), LOAN, "OUTREACH_SENT", LocalDate.of(2026, 11, 20), 15_000, true);
        when(hardship.openCaseOf(MEMBER)).thenReturn(Optional.of(view));

        assertThat(reads.openHardshipCase(MEMBER)).containsSame(view);
        verify(hardship).openCaseOf(MEMBER);
        verifyNoMoreInteractions(hardship);
    }

    @Test
    void aMemberWithNothingGetsEmptyAnswersNotAnError() {
        when(hardship.openCaseOf(MEMBER)).thenReturn(Optional.empty());

        assertThat(reads.goals(MEMBER)).isEmpty();
        assertThat(reads.loans(MEMBER)).isEmpty();
        assertThat(reads.applications(MEMBER)).isEmpty();
        assertThat(reads.openHardshipCase(MEMBER)).isEmpty();
    }
}
