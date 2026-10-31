package com.paypalseed.engagement.infrastructure.events;

import com.paypalseed.engagement.application.usecases.ReminderService;
import com.paypalseed.engagement.domain.model.ReminderTemplates.Type;
import com.paypalseed.lending.api.events.LendingEvents;
import com.paypalseed.payments.api.events.PaymentEvents;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Lending and payment facts that warrant a message to the member. */
@Component
class MemberMessageTriggers {

    private final ReminderService reminders;

    MemberMessageTriggers(ReminderService reminders) {
        this.reminders = reminders;
    }

    @ApplicationModuleListener
    void on(LendingEvents.InstalmentDueSoon e) {
        reminders.remind(e.memberId(), e.loanId(), Type.DUE_SOON, "v" + e.version() + ":s" + e.seq(), e.amountCents(), e.dueDate());
    }

    @ApplicationModuleListener
    void on(LendingEvents.RepaymentFailed e) {
        reminders.remind(e.memberId(), e.loanId(), Type.PAYMENT_FAILED, "s" + e.seq() + ":a" + e.attemptNo(),
                e.amountCents(), e.dueDate());
    }

    @ApplicationModuleListener
    void on(LendingEvents.GraceNudgeDue e) {
        reminders.remind(e.memberId(), e.loanId(), Type.GRACE_NUDGE, "v" + e.version() + ":s" + e.seq(), e.overdueCents(),
                e.dueDate());
    }

    /** PayPal needs the member's consent (or the billing date drifted): tell them before a payment is missed. */
    @ApplicationModuleListener
    void on(PaymentEvents.MandateDriftDetected e) {
        reminders.remind(e.memberId(), e.loanId(), Type.AUTOPAY_ATTENTION, e.eventId().toString(), 0, null);
    }

    @ApplicationModuleListener
    void on(LendingEvents.CollectionModeChanged e) {
        if ("MANUAL".equals(e.toMode()) && e.cause().endsWith("MEMBER")) {
            reminders.remind(e.memberId(), e.loanId(), Type.AUTOPAY_STOPPED, e.eventId().toString(), 0, null);
        }
    }
}
