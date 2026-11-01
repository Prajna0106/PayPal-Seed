package com.paypalseed.savings;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.messaging.EventOutbox;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CreateGoalUseCaseTest {

    private InMemorySavingsGoalRepository repository;
    private DummyEventOutbox outbox;
    private CreateGoalUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = new InMemorySavingsGoalRepository();
        outbox = new DummyEventOutbox();
        useCase = new CreateGoalUseCase(repository, outbox);
    }

    @Test
    void allowsCreatingUpToThreeActiveGoals() {
        String memberId = "member-1";

        useCase.execute(new CreateGoalUseCase.Command(memberId, "Goal 1", Money.usd(100), Money.usd(10), null, "s1"));
        useCase.execute(new CreateGoalUseCase.Command(memberId, "Goal 2", Money.usd(200), Money.usd(20), null, "s2"));
        useCase.execute(new CreateGoalUseCase.Command(memberId, "Goal 3", Money.usd(300), Money.usd(30), null, "s3"));

        assertEquals(3, repository.countActiveByMemberId(memberId));
        assertEquals(3, outbox.events.size());

        // 4th goal creation should be rejected
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            useCase.execute(new CreateGoalUseCase.Command(memberId, "Goal 4", Money.usd(400), Money.usd(40), null, "s4"))
        );

        assertTrue(ex.getMessage().contains("Maximum limit of 3 active goals reached"));
    }

    // In-memory test doubles
    static class InMemorySavingsGoalRepository implements SavingsGoalRepository {
        private final Map<UUID, SavingsGoal> storage = new HashMap<>();

        @Override
        public SavingsGoal save(SavingsGoal goal) {
            storage.put(goal.getId(), goal);
            return goal;
        }

        @Override
        public Optional<SavingsGoal> findById(UUID id) {
            return Optional.ofNullable(storage.get(id));
        }

        @Override
        public List<SavingsGoal> findByMemberId(String memberId) {
            return storage.values().stream().filter(g -> g.getMemberId().equals(memberId)).toList();
        }

        @Override
        public Optional<SavingsGoal> findByPayPalSubscriptionId(String subscriptionId) {
            return storage.values().stream().filter(g -> subscriptionId.equals(g.getPayPalSubscriptionId())).findFirst();
        }

        @Override
        public long countActiveByMemberId(String memberId) {
            return storage.values().stream()
                .filter(g -> g.getMemberId().equals(memberId) && g.getStatus() == com.paypalseed.savings.domain.model.GoalStatus.ACTIVE)
                .count();
        }
    }

    static class DummyEventOutbox implements EventOutbox {
        final List<Object> events = new ArrayList<>();
        @Override
        public void publish(Object event) {
            events.add(event);
        }
    }
}
