package com.paypalseed.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.dto.DepositFact;
import com.paypalseed.savings.api.dto.GoalDepositResponse;
import com.paypalseed.savings.api.dto.MemberGoalView;
import com.paypalseed.savings.application.SavingsFacadeImpl;
import com.paypalseed.savings.application.ports.CreditedPayments;
import com.paypalseed.savings.application.ports.SavingsPayments;
import com.paypalseed.savings.application.usecases.PauseResumeGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.GoalStatus;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.infrastructure.persistence.SavingsRepository;
import com.paypalseed.savings.infrastructure.web.SavingsController;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

/**
 * The next deposit date a goal shows (PayPal's own time, in the business zone, only for a goal that saves by
 * subscription) and the list of deposits that reached a goal (ADR 0027, closing pass).
 */
class NextDepositAndDepositListTest {

    private static final String ANA = "11111111-1111-1111-1111-111111111111";
    private static final Instant BILLING = Instant.parse("2026-12-01T03:30:00Z"); // 19:30 on 30 November in Los Angeles

    private final SavingsRepository repository = new SavingsRepository();

    /** Answers with a billing time for every goal and counts the asks. */
    private static final class Payments implements SavingsPayments {
        Instant next = BILLING;
        int asked;

        @Override
        public Optional<Instant> nextBillingTime(UUID goalId) {
            asked++;
            return Optional.ofNullable(next);
        }

        // The rest of the port is not part of this test.
        @Override
        public Subscription startSubscription(UUID memberId, UUID goalId, long monthlyCents, String requestId, String returnUrl,
                                              String cancelUrl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Subscription> subscriptionOf(UUID goalId) {
            return Optional.empty();
        }

        @Override
        public void suspend(UUID goalId, String requestId, String reason) {
        }

        @Override
        public void activate(UUID goalId, String requestId, String reason) {
        }

        @Override
        public Optional<String> revise(UUID goalId, long monthlyCents, String requestId) {
            return Optional.empty();
        }

        @Override
        public void cancel(UUID goalId, String requestId, String reason) {
        }

        @Override
        public Order startTopUp(UUID memberId, UUID goalId, long amountCents,
                                com.paypalseed.savings.domain.model.SavingsWallet funding, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OrderCapture captureTopUp(UUID goalId, String orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Payout sendPayout(String referenceId, UUID memberId, long amountCents,
                                 com.paypalseed.savings.application.ports.PayoutRecipients.Recipient recipient,
                                 com.paypalseed.savings.domain.model.SavingsWallet wallet, String note) {
            throw new UnsupportedOperationException();
        }
    }

    /** Remembers what it was told to credit, newest first on read. */
    private static final class Credits implements CreditedPayments {
        final List<Credit> credits = new ArrayList<>();
        final List<DepositFact> facts = new ArrayList<>();

        @Override
        public boolean claim(String paypalTxnId, UUID goalId, long amountCents, Source source) {
            return true;
        }

        @Override
        public List<Credit> newestOfGoal(UUID goalId, int limit) {
            return credits.stream().sorted((a, b) -> b.creditedAt().compareTo(a.creditedAt())).limit(limit).toList();
        }

        @Override
        public List<String> membersWithCredits(String afterMemberId, int limit) {
            return facts.stream().map(DepositFact::memberId).distinct().sorted()
                .filter(m -> afterMemberId == null || m.compareTo(afterMemberId) > 0).limit(limit).toList();
        }

        @Override
        public List<DepositFact> creditsOfMember(String memberId) {
            return facts.stream().filter(f -> f.memberId().equals(memberId)).toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private SavingsFacadeImpl facade(Payments payments, Credits credits) {
        return new SavingsFacadeImpl(mock(RecordDepositUseCase.class), mock(PauseResumeGoalUseCase.class), repository,
            provider(payments), provider(credits));
    }

    private SavingsGoal goal(GoalStatus status) {
        Instant created = Instant.parse("2026-09-01T10:00:00Z");
        return repository.save(new SavingsGoal(UUID.randomUUID(), ANA, "Mixer", Money.usd(1_200), Money.usd(75),
            LocalDate.of(2027, 6, 1), "I-Mixer", status, Money.usd(0), 0, created, created));
    }

    @Test
    void anActiveGoalShowsTheBusinessDateOfPayPalsNextCharge() {
        goal(GoalStatus.ACTIVE);

        List<MemberGoalView> goals = facade(new Payments(), new Credits()).goalsOfMember(ANA);

        // 03:30 UTC on 1 December is the evening of 30 November where the program's dates are cut.
        assertThat(goals).singleElement().extracting(MemberGoalView::nextDepositDate).isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    void aGoalThatIsNotSavingShowsNoDateAndPayPalIsNotAskedAboutIt() {
        goal(GoalStatus.PAUSED);
        goal(GoalStatus.PENDING_APPROVAL);
        goal(GoalStatus.CANCELLED);
        Payments payments = new Payments();

        List<MemberGoalView> goals = facade(payments, new Credits()).goalsOfMember(ANA);

        assertThat(goals).hasSize(3).allSatisfy(g -> assertThat(g.nextDepositDate()).isNull());
        assertThat(payments.asked).isZero();
    }

    @Test
    void aGoalPayPalHasNoTimeForAndAFacadeWithoutPaymentsShowNoDate() {
        goal(GoalStatus.ACTIVE);
        Payments silent = new Payments();
        silent.next = null;

        assertThat(facade(silent, new Credits()).goalsOfMember(ANA)).singleElement()
            .extracting(MemberGoalView::nextDepositDate).isNull();
        assertThat(new SavingsFacadeImpl(mock(RecordDepositUseCase.class), mock(PauseResumeGoalUseCase.class), repository)
            .goalsOfMember(ANA)).singleElement().extracting(MemberGoalView::nextDepositDate).isNull();
    }

    @Test
    void theFacadePagesMembersWithDepositsAndGivesTheirDeposits() {
        Credits credits = new Credits();
        UUID goal = UUID.randomUUID();
        credits.facts.add(new DepositFact("T-2", "bbb", goal, 500, Instant.parse("2026-11-01T06:30:00Z")));
        credits.facts.add(new DepositFact("T-1", "aaa", goal, 700, Instant.parse("2026-10-01T06:30:00Z")));
        SavingsFacadeImpl facade = facade(new Payments(), credits);

        assertThat(facade.membersWithDeposits(null, 1)).containsExactly("aaa");
        assertThat(facade.membersWithDeposits("aaa", 10)).containsExactly("bbb");
        assertThat(facade.membersWithDeposits("bbb", 10)).isEmpty();
        assertThat(facade.depositsOfMember("aaa")).extracting(DepositFact::depositKey).containsExactly("T-1");
    }

    // ---- GET /goals/{id}/deposits ----

    private SavingsController controller(Credits credits) {
        return new SavingsController(null, null, null, null, repository, null, null, null, null, null, null, null, null, credits);
    }

    @Test
    void theDepositsOfAGoalComeNewestFirstInDollarsAndCents() {
        SavingsGoal goal = goal(GoalStatus.ACTIVE);
        Credits credits = new Credits();
        credits.credits.add(new CreditedPayments.Credit(CreditedPayments.Source.SUBSCRIPTION, 2_500, Instant.parse("2026-10-03T17:00:00Z")));
        credits.credits.add(new CreditedPayments.Credit(CreditedPayments.Source.TOPUP, 1_050, Instant.parse("2026-11-03T17:00:00Z")));

        ResponseEntity<List<GoalDepositResponse>> response = controller(credits).listDeposits(goal.getId(), null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).extracting(GoalDepositResponse::source).containsExactly("TOPUP", "SUBSCRIPTION");
        GoalDepositResponse newest = response.getBody().get(0);
        assertThat(newest.amountCents()).isEqualTo(1_050);
        assertThat(newest.amount()).isEqualByComparingTo("10.50");
        assertThat(newest.currency()).isEqualTo("USD");
        assertThat(newest.creditedAt()).isEqualTo(Instant.parse("2026-11-03T17:00:00Z"));
    }

    @Test
    void theLimitCutsTheListAndAnOutOfRangeLimitIsRefused() {
        SavingsGoal goal = goal(GoalStatus.ACTIVE);
        Credits credits = new Credits();
        for (int i = 1; i <= 5; i++) {
            credits.credits.add(new CreditedPayments.Credit(CreditedPayments.Source.SUBSCRIPTION, 100L * i,
                Instant.parse("2026-0" + i + "-03T17:00:00Z")));
        }

        assertThat(controller(credits).listDeposits(goal.getId(), 2, null).getBody()).extracting(GoalDepositResponse::amountCents)
            .containsExactly(500L, 400L);
        assertThatThrownBy(() -> controller(credits).listDeposits(goal.getId(), 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller(credits).listDeposits(goal.getId(), 201, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownGoalIsNotFound() {
        assertThatThrownBy(() -> controller(new Credits()).listDeposits(UUID.randomUUID(), null, null))
            .isInstanceOf(DomainException.class);
    }

    @Test
    void aGoalWithNoDepositsHasAnEmptyList() {
        SavingsGoal goal = goal(GoalStatus.ACTIVE);

        assertThat(controller(new Credits()).listDeposits(goal.getId(), null, null).getBody()).isEmpty();
    }
}
