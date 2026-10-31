package com.paypalseed.payments.infrastructure.events;

import com.paypalseed.payments.application.usecases.InboxProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ingress and worker roles drain the inbox; the api role sets {@code seed.jobs.enabled=false}. */
@Component
@ConditionalOnProperty(name = "seed.jobs.enabled", havingValue = "true", matchIfMissing = true)
class InboxPoller {

    private final InboxProcessor inbox;

    InboxPoller(InboxProcessor inbox) {
        this.inbox = inbox;
    }

    @Scheduled(fixedDelayString = "${seed.jobs.inbox-poll-ms:1000}")
    void drain() {
        inbox.drain(200);
    }
}
