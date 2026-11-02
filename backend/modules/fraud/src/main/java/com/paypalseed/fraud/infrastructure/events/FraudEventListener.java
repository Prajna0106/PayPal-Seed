package com.paypalseed.fraud.infrastructure.events;

import com.paypalseed.fraud.api.FraudFacade;
import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.api.dto.FraudDtos.Observation;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.events.LendingEvents.InstalmentView;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentFailed;
import com.paypalseed.lending.api.events.LendingEvents.ScheduleGenerated;
import com.paypalseed.payments.api.events.PaymentEvents.PaymentCaptured;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.savings.api.events.DepositReceivedEvent;
import com.paypalseed.savings.api.events.GoalCreatedEvent;
import com.paypalseed.savings.api.events.WithdrawalProcessedEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns what the platform already publishes into facts. A fact is stamped with the business clock when it arrives, so the
 * rules compare like with like even when the demo clock runs ahead of real time. Delivery is at least once: every
 * observation carries its event id as source key, so a repeat adds nothing.
 *
 * <p>Not fed by any event yet, and so only by {@code FraudFacade.observe}: account creation, loan applications and a change
 * of payout recipient.
 */
@Component
class FraudEventListener {

    private final FraudFacade fraud;
    private final LendingFacade lending;
    private final BusinessClock clock;

    FraudEventListener(FraudFacade fraud, LendingFacade lending, BusinessClock clock) {
        this.fraud = fraud;
        this.lending = lending;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(GoalCreatedEvent e) {
        observe(memberUuid(e.memberId()), FactKind.GOAL_CREATED, "goal:" + e.eventId(), null, null,
                Map.of("goalId", e.goalId().toString()));
    }

    @ApplicationModuleListener
    void on(DepositReceivedEvent e) {
        observe(memberUuid(e.memberId()), FactKind.DEPOSIT, "deposit:" + e.eventId(), e.amount().cents(), null,
                Map.of("goalId", e.goalId().toString()));
    }

    @ApplicationModuleListener
    void on(WithdrawalProcessedEvent e) {
        observe(memberUuid(e.memberId()), FactKind.WITHDRAWAL, "withdrawal:" + e.eventId(), e.amount().cents(), null,
                Map.of("goalId", e.goalId().toString()));
    }

    /** The payer id is hashed inside the fraud module; a capture without one, or for an unknown loan, says nothing. */
    @ApplicationModuleListener
    void on(PaymentCaptured e) {
        if (e.payerRef() == null || e.payerRef().isBlank()) {
            return;
        }
        lending.loan(e.loanId()).ifPresent(loan -> observe(loan.memberId(), FactKind.PAYER_SEEN, "payer:" + e.eventId(),
                null, e.payerRef(), Map.of("loanId", e.loanId().toString())));
    }

    @ApplicationModuleListener
    void on(RepaymentFailed e) {
        observe(e.memberId(), FactKind.PAYMENT_FAILED, "failed:" + e.eventId(), e.amountCents(), null,
                Map.of("loanId", e.loanId().toString()));
    }

    /** Version 1 of a schedule exists once the loan was paid out. */
    @ApplicationModuleListener
    void on(ScheduleGenerated e) {
        observe(e.memberId(), FactKind.LOAN_DISBURSED, "loan:" + e.eventId(),
                e.instalments().stream().mapToLong(InstalmentView::amountCents).sum(), null,
                Map.of("loanId", e.loanId().toString()));
    }

    private void observe(UUID memberId, FactKind kind, String sourceKey, Long amountCents, String reference,
                         Map<String, String> attributes) {
        fraud.observe(new Observation(memberId, kind, clock.now(), sourceKey, amountCents, reference, attributes));
    }

    /** Savings keeps its member ids as strings; a non-UUID id maps to the same UUID savings uses for the ledger. */
    static UUID memberUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        }
    }
}
