package com.paypalseed.reserve.infrastructure.events;

import com.paypalseed.governance.api.events.GovernanceEvents.ApprovalDecided;
import com.paypalseed.lending.api.events.LendingEvents.LoanReopened;
import com.paypalseed.lending.api.events.LendingEvents.LoanRepaid;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReceived;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReversed;
import com.paypalseed.lending.api.events.LendingEvents.ScheduleGenerated;
import com.paypalseed.payments.api.events.PaymentEvents.DonationCaptured;
import com.paypalseed.reserve.api.ReserveFacade;
import com.paypalseed.reserve.application.usecases.CoverService;
import com.paypalseed.reserve.application.usecases.ExposureTracker;
import com.paypalseed.reserve.domain.model.DonationCustomId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Lending's balances, which make up the open exposure, governance's closed approvals, which bring a cover request
 * to its end when a decision did not arrive through the desk, and payments' captured donations. Delivery is at least
 * once: every handler is idempotent.
 */
@Component
class ReserveEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReserveEventListener.class);

    private final ExposureTracker exposure;
    private final CoverService covers;
    private final ReserveFacade reserve;

    ReserveEventListener(ExposureTracker exposure, CoverService covers, ReserveFacade reserve) {
        this.exposure = exposure;
        this.covers = covers;
        this.reserve = reserve;
    }

    @ApplicationModuleListener
    void on(ScheduleGenerated e) {
        exposure.on(e);
    }

    @ApplicationModuleListener
    void on(RepaymentReceived e) {
        exposure.on(e);
    }

    @ApplicationModuleListener
    void on(RepaymentReversed e) {
        exposure.on(e);
    }

    @ApplicationModuleListener
    void on(LoanReopened e) {
        exposure.on(e);
    }

    @ApplicationModuleListener
    void on(LoanRepaid e) {
        exposure.on(e);
    }

    @ApplicationModuleListener
    void on(ApprovalDecided e) {
        if (!"OPEN".equals(e.requestStatus())) {
            covers.onApprovalClosed(e.requestId());
        }
    }

    /**
     * A PayPal capture that carries a donation's custom id becomes a gift, idempotent on the PayPal transaction id. An
     * event that cannot be a gift (a foreign custom id, a non-positive amount, a fee that eats the gift) is dropped
     * with a log line instead of failing, because a listener that always throws would be retried forever. A custom id
     * of ours that names no known intent is still a gift, recorded as anonymous: the money did arrive. Only ids and
     * counts are logged, never a donor choice.
     */
    @ApplicationModuleListener
    void on(DonationCaptured e) {
        if (e.customId() == null || !e.customId().startsWith(DonationCustomId.PREFIX)) {
            log.warn("Donation capture {} ignored: its custom id is not a reserve donation id", e.paypalTxnId());
            return;
        }
        if (e.grossCents() <= 0 || e.feeCents() < 0 || e.feeCents() >= e.grossCents()) {
            log.warn("Donation capture {} ignored: gross {} and fee {} cents cannot be a gift", e.paypalTxnId(), e.grossCents(),
                    e.feeCents());
            return;
        }
        if (DonationCustomId.parse(e.customId()).isEmpty()) {
            log.warn("Donation capture {} has a malformed custom id; recording it as an anonymous gift", e.paypalTxnId());
        }
        reserve.recordDonationForCustomId(e.customId(), e.paypalTxnId(), e.grossCents(), e.feeCents());
    }
}
