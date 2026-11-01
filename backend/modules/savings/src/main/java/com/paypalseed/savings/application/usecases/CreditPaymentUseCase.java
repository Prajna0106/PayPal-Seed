package com.paypalseed.savings.application.usecases;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.application.ports.CreditedPayments;
import com.paypalseed.savings.application.ports.CreditedPayments.Source;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds a PayPal payment to its goal exactly once. Events reach this at least once, so the PayPal transaction
 * (or capture) id is claimed first; a payment already credited changes nothing. A deposit the goal cannot take
 * yet (the member has not approved the subscription) throws, which rolls the claim back and leaves the event
 * to be delivered again. A payment for a goal Seed has no record of can never succeed on a retry, so it is logged
 * and dropped: the event completes instead of being redelivered for ever.
 */
public class CreditPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreditPaymentUseCase.class);

    private final CreditedPayments credited;
    private final RecordDepositUseCase deposits;
    private final SavingsGoalRepository goals;

    public CreditPaymentUseCase(CreditedPayments credited, RecordDepositUseCase deposits, SavingsGoalRepository goals) {
        this.credited = Objects.requireNonNull(credited);
        this.deposits = Objects.requireNonNull(deposits);
        this.goals = Objects.requireNonNull(goals);
    }

    /** True when this call credited the goal, false when the payment had been credited before or its goal is unknown. */
    @Transactional
    public boolean credit(UUID goalId, String paypalTxnId, long amountCents, Source source) {
        if (goals.findById(goalId).isEmpty()) {
            log.warn("Payment {} ({}) is for unknown goal {}; not credited", paypalTxnId, source, goalId);
            return false;
        }
        if (!credited.claim(paypalTxnId, goalId, amountCents, source)) {
            return false;
        }
        deposits.execute(new RecordDepositUseCase.Command(goalId, Money.ofCents(amountCents), paypalTxnId));
        return true;
    }
}
