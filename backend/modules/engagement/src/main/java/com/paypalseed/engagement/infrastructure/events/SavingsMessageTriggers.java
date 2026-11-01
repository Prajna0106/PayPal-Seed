package com.paypalseed.engagement.infrastructure.events;

import com.paypalseed.engagement.application.usecases.ReminderService;
import com.paypalseed.engagement.domain.model.SavingsMessages;
import com.paypalseed.savings.api.events.WithdrawalFailedEvent;
import com.paypalseed.savings.api.events.WithdrawalReturnedEvent;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Savings facts that reach the member: a withdrawal PayPal could not deliver, or took back. Each withdrawal yields at
 * most one message per outcome (keyed by the withdrawal, not the event), so a redelivered event never texts twice, and
 * the shared queue applies consent (STOP is honoured), quiet hours and the in-app copy exactly as for every other message.
 */
@Component
class SavingsMessageTriggers {

    static final String FAILED = "WITHDRAWAL_FAILED";
    static final String RETURNED = "WITHDRAWAL_RETURNED";

    private final ReminderService reminders;

    SavingsMessageTriggers(ReminderService reminders) {
        this.reminders = reminders;
    }

    @ApplicationModuleListener
    void on(WithdrawalFailedEvent e) {
        long cents = e.amount().cents();
        reminders.notifyAboutGoal(memberUuid(e.memberId()), e.goalId(), FAILED, "withdrawal:" + e.withdrawalId() + ":failed",
                locale -> SavingsMessages.withdrawalFailed(locale, cents));
    }

    @ApplicationModuleListener
    void on(WithdrawalReturnedEvent e) {
        long cents = e.amount().cents();
        reminders.notifyAboutGoal(memberUuid(e.memberId()), e.goalId(), RETURNED, "withdrawal:" + e.withdrawalId() + ":returned",
                locale -> SavingsMessages.withdrawalReturned(locale, cents));
    }

    /** Savings keeps its member ids as text; a name that is not a UUID maps to the same UUID savings uses elsewhere. */
    static UUID memberUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8));
        }
    }
}
