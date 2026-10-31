package com.paypalseed.payments.application;

public interface WebhookInbox {
    /**
     * Checks if this PayPal event ID has already been successfully ingested.
     */
    boolean isProcessed(String eventId);

    /**
     * Marks the PayPal event ID as processed to guarantee idempotency.
     */
    void markProcessed(String eventId, String eventType);
}
