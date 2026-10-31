package com.paypalseed.engagement.infrastructure.events;

import com.paypalseed.engagement.application.usecases.ReminderService;
import com.paypalseed.engagement.domain.model.HardshipMessages;
import com.paypalseed.hardship.api.events.HardshipEvents;
import java.util.List;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Hardship facts that reach the member. The first offer of a revision is keyed by the revision, so a redelivered
 * event never texts twice; reminders and re-sends are keyed by their own event, which hardship emits at most once.
 */
@Component
class HardshipMessageTriggers {

    private final ReminderService reminders;

    HardshipMessageTriggers(ReminderService reminders) {
        this.reminders = reminders;
    }

    @ApplicationModuleListener
    void on(HardshipEvents.HardshipOfferIssued e) {
        List<HardshipMessages.OfferedOption> options = e.options().stream()
                .map(o -> new HardshipMessages.OfferedOption(o.label(), o.recommended(), o.summary()))
                .toList();
        String key = e.reminder()
                ? "hardship:" + e.caseId() + ":" + e.eventId()
                : "hardship:" + e.caseId() + ":r" + e.revision() + ":offer";
        reminders.notify(e.memberId(), e.loanId(), e.reminder() ? "HARDSHIP_REMINDER" : "HARDSHIP_OFFER", key,
                locale -> HardshipMessages.offer(locale, e.memberMessages().getOrDefault(locale, e.memberMessages().get("en")),
                        options, e.reminder()));
    }

    @ApplicationModuleListener
    void on(HardshipEvents.HardshipApplied e) {
        reminders.notify(e.memberId(), e.loanId(), "HARDSHIP_UPDATE", "hardship:" + e.caseId() + ":applied",
                HardshipMessages::applied);
    }

    @ApplicationModuleListener
    void on(HardshipEvents.HardshipDecisionMade e) {
        if ("REJECT".equals(e.decision())) {
            reminders.notify(e.memberId(), e.loanId(), "HARDSHIP_UPDATE", "hardship:" + e.caseId() + ":rejected",
                    HardshipMessages::rejected);
        }
    }
}
