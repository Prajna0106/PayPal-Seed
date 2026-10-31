package com.paypalseed.payments.infrastructure.persistence;

import com.paypalseed.payments.application.WebhookInbox;
import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryWebhookInbox implements WebhookInbox {

    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isProcessed(String eventId) {
        if (eventId == null || eventId.trim().isEmpty()) {
            return false;
        }
        return processedEventIds.contains(eventId);
    }

    @Override
    public void markProcessed(String eventId, String eventType) {
        if (eventId != null && !eventId.trim().isEmpty()) {
            processedEventIds.add(eventId);
        }
    }

    public void clear() {
        processedEventIds.clear();
    }
}
