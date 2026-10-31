package com.paypalseed.engagement.infrastructure.events;

import com.paypalseed.engagement.application.usecases.ReminderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Worker role: sends queued SMS whose quiet hours have passed. */
@Component
@ConditionalOnProperty(name = "seed.jobs.enabled", havingValue = "true", matchIfMissing = true)
class ReminderPoller {

    private final ReminderService reminders;

    ReminderPoller(ReminderService reminders) {
        this.reminders = reminders;
    }

    @Scheduled(fixedDelayString = "${seed.jobs.reminder-poll-ms:30000}")
    void send() {
        reminders.dispatchDue(100);
    }
}
