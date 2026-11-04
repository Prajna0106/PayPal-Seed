package com.paypalseed.payments.application.usecases;

import com.paypalseed.payments.application.ports.PayPalGateway.PayoutItem;
import com.paypalseed.payments.application.usecases.PayoutFacts.Report;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.payments.infrastructure.persistence.PayoutRepository;
import com.paypalseed.payments.infrastructure.persistence.PayoutRepository.Payout;
import com.paypalseed.platform.kernel.DomainException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The write half of {@link PayoutSyncService}, a bean of its own so the transaction opens only after PayPal has answered. */
@Component
class PayoutSyncStep {

    private final PayoutRepository payouts;
    private final PayoutFacts facts;

    PayoutSyncStep(PayoutRepository payouts, PayoutFacts facts) {
        this.payouts = payouts;
        this.facts = facts;
    }

    /** The payout is locked and read again here, so a webhook applied meanwhile is taken into account. */
    @Transactional
    Payout apply(UUID payoutId, PayoutItem observed) {
        Payout locked = payouts.byIdForUpdate(payoutId)
                .orElseThrow(() -> new DomainException(PaymentsErrors.PAYOUT_NOT_FOUND, "No such payout"));
        return facts.apply(locked, Report.of(observed, PayoutFacts.POLL, null));
    }
}
