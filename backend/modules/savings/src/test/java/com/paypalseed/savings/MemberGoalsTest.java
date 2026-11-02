package com.paypalseed.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.dto.MemberGoalView;
import com.paypalseed.savings.application.SavingsFacadeImpl;
import com.paypalseed.savings.application.usecases.PauseResumeGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.GoalStatus;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.infrastructure.persistence.SavingsRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The member's goal list: only that member's goals, newest first, as whole cents and nothing from PayPal. */
class MemberGoalsTest {

    private static final String ANA = "11111111-1111-1111-1111-111111111111";
    private static final String BEA = "22222222-2222-2222-2222-222222222222";

    private final SavingsRepository repository = new SavingsRepository();
    private final SavingsFacadeImpl facade =
        new SavingsFacadeImpl(mock(RecordDepositUseCase.class), mock(PauseResumeGoalUseCase.class), repository);

    private SavingsGoal goal(String member, String name, double saved, Instant createdAt) {
        return repository.save(new SavingsGoal(UUID.randomUUID(), member, name, Money.usd(1_200), Money.usd(75),
            LocalDate.of(2027, 6, 1), "I-" + name, GoalStatus.ACTIVE, Money.usd(saved), 2, createdAt, createdAt));
    }

    @Test
    void theMembersGoalsComeNewestFirstInWholeCents() {
        goal(ANA, "Oven", 150, Instant.parse("2026-09-01T10:00:00Z"));
        SavingsGoal newer = goal(ANA, "Mixer", 75, Instant.parse("2026-10-01T10:00:00Z"));

        List<MemberGoalView> goals = facade.goalsOfMember(ANA);

        assertThat(goals).extracting(MemberGoalView::name).containsExactly("Mixer", "Oven");
        MemberGoalView first = goals.get(0);
        assertThat(first.goalId()).isEqualTo(newer.getId());
        assertThat(first.status()).isEqualTo("ACTIVE");
        assertThat(first.targetCents()).isEqualTo(120_000);
        assertThat(first.savedCents()).isEqualTo(7_500);
        assertThat(first.monthlyCents()).isEqualTo(7_500);
        assertThat(first.currency()).isEqualTo("USD");
        assertThat(first.targetDate()).isEqualTo(LocalDate.of(2027, 6, 1));
    }

    @Test
    void anotherMembersGoalsAndAnUnknownMemberAreNotInTheList() {
        goal(ANA, "Oven", 150, Instant.parse("2026-09-01T10:00:00Z"));
        goal(BEA, "Bike", 20, Instant.parse("2026-09-02T10:00:00Z"));

        assertThat(facade.goalsOfMember(BEA)).extracting(MemberGoalView::name).containsExactly("Bike");
        assertThat(facade.goalsOfMember(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void theViewCarriesNoPayPalReferenceAndNoApprovalLink() {
        goal(ANA, "Oven", 150, Instant.parse("2026-09-01T10:00:00Z"));

        assertThat(facade.goalsOfMember(ANA).toString()).doesNotContain("I-Oven").doesNotContain("approval");
    }
}
