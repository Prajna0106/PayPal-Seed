package com.paypalseed.lending.infrastructure.events;

import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.payments.api.events.PaymentEvents;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The end of a loan payout, as the payments module announces it. Runs after the publishing transaction commits, in
 * its own; a failure leaves the publication incomplete so it is delivered again. Each handler ignores a reference
 * that is not the current payout of an application, so savings withdrawals (also paid through Payouts) and reports
 * about an earlier attempt change nothing, and a repeat changes nothing either.
 */
@Component
class PayoutEventsListener {

    private final MicroloanApplicationService applications;

    PayoutEventsListener(MicroloanApplicationService applications) {
        this.applications = applications;
    }

    @ApplicationModuleListener
    void on(PaymentEvents.PayoutSucceeded e) {
        applications.onPayoutSucceeded(e.referenceId());
    }

    @ApplicationModuleListener
    void on(PaymentEvents.PayoutFailed e) {
        applications.onPayoutFailed(e.referenceId(), e.reasonCode(), e.terminal());
    }

    @ApplicationModuleListener
    void on(PaymentEvents.PayoutReturned e) {
        applications.onPayoutReturned(e.referenceId(), e.reasonCode());
    }
}
