package com.paypalseed.engagement.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.engagement.application.usecases.ReminderService;
import com.paypalseed.engagement.domain.model.SmsKeywords;
import com.paypalseed.engagement.domain.model.SmsKeywords.Keyword;
import com.paypalseed.engagement.infrastructure.clients.ZapierSmsClient;
import com.paypalseed.engagement.infrastructure.persistence.ReminderRepository;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Member SMS replies relayed by Zapier, verified by HMAC and parsed with {@link SmsKeywords}. STOP
 * and START change the opt-out. Other keywords are acknowledged and logged only.
 */
@RestController
class SmsInboundController {

    private static final Logger log = LoggerFactory.getLogger(SmsInboundController.class);

    record Ack(String keyword) {
    }

    private final ZapierSmsClient zapier;
    private final ReminderRepository contacts;
    private final ReminderService reminders;
    private final ObjectMapper json;

    SmsInboundController(ZapierSmsClient zapier, ReminderRepository contacts, ReminderService reminders, ObjectMapper json) {
        this.zapier = zapier;
        this.contacts = contacts;
        this.reminders = reminders;
        this.json = json;
    }

    @PostMapping(path = "/api/v1/webhooks/zapier/sms", consumes = "application/json")
    Ack receive(@RequestHeader(name = "X-Seed-Timestamp", required = false) String timestamp,
                @RequestHeader(name = "X-Seed-Signature", required = false) String signature,
                @RequestBody String body) {
        if (!zapier.verify(timestamp, signature, body)) {
            throw new DomainException(CommonErrors.UNAUTHENTICATED, "Invalid Zapier signature");
        }
        JsonNode message;
        try {
            message = json.readTree(body);
        } catch (IOException e) {
            throw new DomainException(CommonErrors.UNAUTHENTICATED, "Body is not JSON");
        }
        Keyword keyword = SmsKeywords.parse(message.path("text").asText(""));
        Optional<UUID> member = contacts.memberByPhone(message.path("from").asText(""));
        if (member.isEmpty()) {
            return new Ack(Keyword.UNKNOWN.name());
        }
        switch (keyword) {
            case STOP -> reminders.setOptOut(member.get(), true);
            case START -> reminders.setOptOut(member.get(), false);
            default -> log.info("SMS keyword {} from member {}", keyword, member.get());
        }
        return new Ack(keyword.name());
    }
}
