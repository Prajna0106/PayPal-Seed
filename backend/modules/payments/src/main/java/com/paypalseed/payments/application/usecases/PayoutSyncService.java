package com.paypalseed.payments.application.usecases;

import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutSyncSummary;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutView;
import com.paypalseed.payments.application.ports.PayPalException;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.ports.PayPalGateway.PayoutItem;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.payments.infrastructure.persistence.PayoutRepository;
import com.paypalseed.payments.infrastructure.persistence.PayoutRepository.Payout;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Pulls what a payout webhook would have pushed (ADR 0029). Seed learns how a payout ended only from PayPal's webhooks, which
 * cannot reach a machine with no public URL and can be lost in production; this reads the item (or, before an item id is
 * known, the batch) from PayPal and hands it to {@link PayoutFacts} exactly like a webhook report. That one step moves the
 * payout forward at most once and publishes the one event for each outcome, so a webhook that comes before, after or twice
 * changes nothing. A final payout is never read; a delivered one still is when asked for (PayPal can take it back), but the
 * reconcile job leaves it alone.
 *
 * <p>No transaction here: the right to read is taken with one committed statement before PayPal is called, so a read that
 * fails still counts against the minimum gap, and the report is applied in its own short transaction ({@link PayoutSyncStep}).
 */
@Service
class PayoutSyncService {

    private static final Logger log = LoggerFactory.getLogger(PayoutSyncService.class);

    /** What one call did: the payout as stored afterwards, and whether PayPal was read at all. */
    record Outcome(PayoutView view, boolean read) {
    }

    private final PayPalGateway paypal;
    private final PayoutRepository payouts;
    private final PayoutSyncStep step;
    private final Clock realTime;
    private final Duration minGap;
    private final Duration minAge;

    @Autowired
    PayoutSyncService(PayPalGateway paypal, PayoutRepository payouts, PayoutSyncStep step, ObjectProvider<Clock> realTime,
                      @Value("${seed.payments.payout-sync-min-interval:PT5S}") Duration minGap,
                      @Value("${seed.payments.payout-reconcile-min-age:PT20S}") Duration minAge) {
        this(paypal, payouts, step, realTime.getIfAvailable(Clock::systemUTC), minGap, minAge);
    }

    PayoutSyncService(PayPalGateway paypal, PayoutRepository payouts, PayoutSyncStep step, Clock realTime, Duration minGap,
                      Duration minAge) {
        this.paypal = paypal;
        this.payouts = payouts;
        this.step = step;
        this.realTime = realTime;
        this.minGap = minGap;
        this.minAge = minAge;
    }

    Outcome sync(UUID payoutId) {
        Payout payout = payouts.byId(payoutId)
                .orElseThrow(() -> new DomainException(PaymentsErrors.PAYOUT_NOT_FOUND, "No such payout"));
        if (payout.status().isFinal() || !readable(payout) || !payouts.claimSync(payoutId, realTime.instant(), minGap)) {
            return new Outcome(PayoutService.view(payout), false);
        }
        return new Outcome(PayoutService.view(step.apply(payoutId, read(payout))), true);
    }

    /** One bounded batch of payouts that may have missed a webhook; a payout that fails is logged and left for the next run. */
    PayoutSyncSummary syncOpen(int limit) {
        List<UUID> due = payouts.dueForSync(realTime.instant().minus(minAge), limit);
        int synced = 0;
        int failed = 0;
        for (UUID id : due) {
            try {
                if (sync(id).read()) {
                    synced++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Could not read payout {} from PayPal: {}", id, e.toString());
            }
        }
        return new PayoutSyncSummary(due.size(), synced, failed);
    }

    /** PayPal is asked by the item id when Seed has it, else by the batch; with neither there is nothing to ask about. */
    private static boolean readable(Payout payout) {
        return payout.paypalItemId() != null || payout.paypalBatchId() != null;
    }

    private PayoutItem read(Payout payout) {
        try {
            return payout.paypalItemId() != null ? paypal.getPayoutItem(payout.paypalItemId())
                    : paypal.getPayoutBatch(payout.paypalBatchId());
        } catch (PayPalException e) {
            if (e.isTransient()) {
                throw new DomainException(CommonErrors.DEPENDENCY_UNAVAILABLE, "PayPal is unavailable; try again");
            }
            throw new DomainException(PaymentsErrors.PAYPAL_REJECTED, "PayPal refused the payout read",
                    e.paypalIssue() == null ? Map.of() : Map.of("paypalIssue", e.paypalIssue()));
        }
    }
}
