package com.paypalseed.savings.application.usecases;

import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.ledger.api.dto.PostingRequest;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.messaging.EventOutbox;
import com.paypalseed.savings.api.events.DepositReceivedEvent;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.domain.model.SavingsGoal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class RecordDepositUseCase {

    private final SavingsGoalRepository repository;
    private final EventOutbox eventOutbox;
    private final LedgerFacade ledger;
    private final BusinessClock clock;

    public RecordDepositUseCase(SavingsGoalRepository repository, EventOutbox eventOutbox) {
        this(repository, eventOutbox, null, null);
    }

    public RecordDepositUseCase(
        SavingsGoalRepository repository,
        EventOutbox eventOutbox,
        LedgerFacade ledger,
        BusinessClock clock
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.ledger = ledger;
        this.clock = clock;
    }

    public record Command(
        UUID goalId,
        Money amount,
        String payPalCaptureId
    ) {}

    @Transactional
    public SavingsGoal execute(Command command) {
        SavingsGoal goal = repository.findById(command.goalId())
            .orElseThrow(() -> new IllegalArgumentException("Savings goal not found: " + command.goalId()));

        goal.recordDeposit(command.amount(), command.payPalCaptureId());
        SavingsGoal saved = repository.save(goal);
        postDepositToLedger(saved, command.amount(), command.payPalCaptureId());

        eventOutbox.publish(DepositReceivedEvent.now(
            saved.getMemberId(),
            saved.getId(),
            command.amount(),
            command.payPalCaptureId()
        ));

        return saved;
    }

    @Transactional
    public SavingsGoal executeForSubscription(String subscriptionId, Money amount, String payPalCaptureId) {
        SavingsGoal goal = repository.findByPayPalSubscriptionId(subscriptionId)
            .orElseThrow(() -> new IllegalArgumentException("Savings goal not found for PayPal subscription: " + subscriptionId));

        goal.recordDeposit(amount, payPalCaptureId);
        SavingsGoal saved = repository.save(goal);
        postDepositToLedger(saved, amount, payPalCaptureId);

        eventOutbox.publish(DepositReceivedEvent.now(
            saved.getMemberId(),
            saved.getId(),
            amount,
            payPalCaptureId
        ));

        return saved;
    }

    private void postDepositToLedger(SavingsGoal goal, Money amount, String payPalCaptureId) {
        if (ledger == null) {
            return;
        }
        String captureKey = (payPalCaptureId != null && !payPalCaptureId.isBlank())
            ? payPalCaptureId.trim()
            : "dep-" + goal.getDepositCount();
        String sourceKey = "savings-deposit:" + goal.getId() + ":" + captureKey;
        LocalDate businessDate = clock != null ? clock.today() : LocalDate.now();
        UUID memberUuid = toMemberUuid(goal.getMemberId());

        ledger.post(PostingRequest.builder(sourceKey, "Savings deposit into goal " + goal.getId(), businessDate)
            .debit(AccountRef.program(AccountType.PROGRAM_CASH), amount.cents())
            .credit(AccountRef.of(AccountType.MEMBER_SAVINGS, memberUuid), amount.cents())
            .build());
    }

    public static UUID toMemberUuid(String memberId) {
        try {
            return UUID.fromString(memberId);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(memberId.getBytes(StandardCharsets.UTF_8));
        }
    }
}
