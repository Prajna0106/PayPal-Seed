package com.paypalseed.payments.application.usecases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.paypalseed.payments.application.ports.PayPalException;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.payments.infrastructure.persistence.CommandRepository;
import com.paypalseed.payments.infrastructure.persistence.CommandRepository.Command;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs every PayPal write through {@code payments.paypal_command}:
 * <ul>
 *   <li>the idempotency key is sent as {@code PayPal-Request-Id}, so a replay after a timeout returns
 *       PayPal's original result instead of moving money twice;</li>
 *   <li>a key already used for a different request is refused ({@code idempotency-key-reused});</li>
 *   <li>a succeeded command replays its stored response without calling PayPal;</li>
 *   <li>transient failures surface as {@code dependency-unavailable} (callers and event listeners
 *       retry with the same key); PayPal refusals surface as {@code paypal-rejected}.</li>
 * </ul>
 */
@Component
public class PayPalCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(PayPalCommandExecutor.class);

    private final CommandRepository commands;
    private final ObjectMapper json;

    public PayPalCommandExecutor(CommandRepository commands, ObjectMapper json) {
        this.commands = commands;
        this.json = json;
    }

    public <T> T run(String kind, UUID aggregateId, String key, Object request, Class<T> responseType,
                     Function<String, T> call) {
        String hash = hash(kind + "|" + aggregateId + "|" + fingerprint(request));
        Command existing = commands.byKey(key).orElse(null);
        if (existing != null) {
            if (!existing.requestHash().equals(hash)) {
                throw new DomainException(CommonErrors.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency key was already used for a different request");
            }
            if ("SUCCEEDED".equals(existing.status())) {
                return read(existing.response(), responseType);
            }
        } else {
            commands.insertPending(Ids.newId(), kind, aggregateId, key, hash);
        }
        try {
            T result = call.apply(key);
            commands.succeeded(key, write(result));
            return result;
        } catch (PayPalException e) {
            commands.failed(key, e.isTransient(), e.getMessage());
            log.warn("PayPal {} for {} failed ({}): {}", kind, aggregateId, e.kind(), e.getMessage());
            if (e.isTransient()) {
                throw new DomainException(CommonErrors.DEPENDENCY_UNAVAILABLE, "PayPal is unavailable; try again");
            }
            throw new DomainException(PaymentsErrors.PAYPAL_REJECTED, "PayPal refused the request",
                    e.paypalIssue() == null ? Map.of() : Map.of("paypalIssue", e.paypalIssue()));
        }
    }

    /** Map entries sorted by key: a request built from a map must hash the same however the map iterates. */
    String fingerprint(Object request) {
        try {
            return json.writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + request.getClass(), e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + value.getClass(), e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read stored PayPal response as " + type, e);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
