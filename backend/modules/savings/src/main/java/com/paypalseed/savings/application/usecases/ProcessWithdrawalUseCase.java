package com.paypalseed.savings.application.usecases;

import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.ledger.api.dto.PostingRequest;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.messaging.EventOutbox;
import com.paypalseed.savings.api.events.WithdrawalProcessedEvent;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.domain.model.SavingsGoal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class ProcessWithdrawalUseCase {

    private final SavingsGoalRepository repository;
    private final EventOutbox eventOutbox;
    private final LedgerFacade ledger;
    private final BusinessClock clock;

    public ProcessWithdrawalUseCase(SavingsGoalRepository repository, EventOutbox eventOutbox) {
        this(repository, eventOutbox, null, null);
    }

    public ProcessWithdrawalUseCase(
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
        String payPalPayoutId
    ) {}

    @Transactional
    public SavingsGoal execute(Command command) {
        SavingsGoal goal = repository.findByIdForUpdate(command.goalId())
            .orElseThrow(() -> new IllegalArgumentException("Savings goal not found: " + command.goalId()));

        goal.recordWithdrawal(command.amount());
        SavingsGoal saved = repository.save(goal);
        postWithdrawalToLedger(saved, command.amount(), command.payPalPayoutId());

        eventOutbox.publish(WithdrawalProcessedEvent.now(
            saved.getMemberId(),
            saved.getId(),
            command.amount(),
            command.payPalPayoutId()
        ));

        return saved;
    }

    private void postWithdrawalToLedger(SavingsGoal goal, Money amount, String payPalPayoutId) {
        if (ledger == null) {
            return;
        }
        String payoutKey = (payPalPayoutId != null && !payPalPayoutId.isBlank())
            ? payPalPayoutId.trim()
            : "wd-" + goal.getCurrentBalance().cents() + "-" + goal.getUpdatedAt().toEpochMilli();
        String sourceKey = "savings-withdrawal:" + goal.getId() + ":" + payoutKey;
        LocalDate businessDate = clock != null ? clock.today() : LocalDate.now();
        UUID memberUuid = RecordDepositUseCase.toMemberUuid(goal.getMemberId());

        ledger.post(PostingRequest.builder(sourceKey, "Savings withdrawal from goal " + goal.getId(), businessDate)
            .debit(AccountRef.of(AccountType.MEMBER_SAVINGS, memberUuid), amount.cents())
            .credit(AccountRef.program(AccountType.PROGRAM_CASH), amount.cents())
            .build());
    }
}
