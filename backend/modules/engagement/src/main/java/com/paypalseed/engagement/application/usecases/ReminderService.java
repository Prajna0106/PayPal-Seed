package com.paypalseed.engagement.application.usecases;

import com.paypalseed.engagement.domain.model.QuietHours;
import com.paypalseed.engagement.domain.model.ReminderTemplates;
import com.paypalseed.engagement.domain.model.ReminderTemplates.Type;
import com.paypalseed.engagement.infrastructure.clients.ZapierSmsClient;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository.Contact;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository.Reminder;
import com.paypalseed.platform.kernel.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-12: decides and sends member reminders. Each fact (loan, schedule version, instalment, type)
 * yields at most one message; quiet hours defer rather than drop; STOP is honoured; members
 * without a phone still get the in-app message.
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final ReminderRepository reminders;
    private final ZapierSmsClient sms;
    private final BusinessClock clock;

    public ReminderService(ReminderRepository reminders, ZapierSmsClient sms, BusinessClock clock) {
        this.reminders = reminders;
        this.sms = sms;
        this.clock = clock;
    }

    @Transactional
    public void remind(UUID memberId, UUID loanId, Type type, String factKey, long amountCents, LocalDate date) {
        notify(memberId, loanId, type.name(), "loan:" + loanId + ":" + factKey + ":" + type,
                locale -> ReminderTemplates.render(type, locale, amountCents, date));
    }

    /**
     * A message whose text the caller renders per locale (hardship offers). Same rules as every reminder: one
     * message per dedupe key, in-app always, SMS only with a phone and no STOP, deferred past quiet hours.
     */
    @Transactional
    public void notify(UUID memberId, UUID loanId, String type, String key, Function<String, String> bodyFor) {
        deliver(memberId, loanId, null, type, key, bodyFor);
    }

    /** As {@link #notify}, for a message about a savings goal (a failed or returned withdrawal) rather than a loan. */
    @Transactional
    public void notifyAboutGoal(UUID memberId, UUID goalId, String type, String key, Function<String, String> bodyFor) {
        deliver(memberId, null, goalId, type, key, bodyFor);
    }

    private void deliver(UUID memberId, UUID loanId, UUID goalId, String type, String key, Function<String, String> bodyFor) {
        Optional<Contact> contact = reminders.contact(memberId);
        String locale = contact.map(Contact::locale).orElse("en");
        String body = bodyFor.apply(locale);
        reminders.enqueue(key + ":IN_APP", memberId, loanId, goalId, type, "IN_APP", locale, body, clock.now(), "SENT");
        String smsStatus = contact.isEmpty() || contact.get().phoneE164() == null ? "SKIPPED_NO_CONTACT"
                : contact.get().optedOut() ? "SKIPPED_OPT_OUT" : "QUEUED";
        Instant sendAfter = contact.map(c -> QuietHours.nextAllowed(clock.now(), ZoneId.of(c.timezone()))).orElse(clock.now());
        reminders.enqueue(key + ":SMS", memberId, loanId, goalId, type, "SMS", locale, body, sendAfter, smsStatus);
    }

    /** Sends due SMS reminders; returns how many were sent. Runs on the worker role. */
    @Transactional
    public int dispatchDue(int limit) {
        List<Reminder> due = reminders.claimDue(Instant.now().isAfter(clock.now()) ? Instant.now() : clock.now(), limit);
        int sent = 0;
        for (Reminder r : due) {
            Optional<Contact> contact = reminders.contact(r.memberId());
            if (contact.isEmpty() || contact.get().optedOut() || contact.get().phoneE164() == null) {
                reminders.mark(r.dedupeKey(), contact.isPresent() && contact.get().optedOut() ? "SKIPPED_OPT_OUT" : "SKIPPED_NO_CONTACT", null);
                continue;
            }
            try {
                sms.send(contact.get().phoneE164(), r.body(), r.dedupeKey());
                reminders.mark(r.dedupeKey(), "SENT", null);
                sent++;
            } catch (RuntimeException e) {
                if (r.attempts() + 1 >= 5) {
                    reminders.mark(r.dedupeKey(), "FAILED", e.getMessage());
                } else {
                    reminders.retryLater(r.dedupeKey(), Instant.now().plus(Duration.ofMinutes(1L << r.attempts())), e.getMessage());
                }
                log.warn("SMS {} failed: {}", r.dedupeKey(), e.getMessage());
            }
        }
        return sent;
    }

    /** SMS opt-out (STOP) and opt-in (START), as required for US text messaging. */
    @Transactional
    public void setOptOut(UUID memberId, boolean optOut) {
        reminders.setOptOut(memberId, optOut);
    }
}
