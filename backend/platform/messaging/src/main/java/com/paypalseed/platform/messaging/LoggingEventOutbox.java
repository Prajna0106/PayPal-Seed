package com.paypalseed.platform.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class LoggingEventOutbox implements EventOutbox {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventOutbox.class);
    private final ApplicationEventPublisher publisher;

    public LoggingEventOutbox(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(Object event) {
        log.info("[OUTBOX] Publishing domain event: {}", event);
        if (publisher != null) {
            publisher.publishEvent(event);
        }
    }
}
