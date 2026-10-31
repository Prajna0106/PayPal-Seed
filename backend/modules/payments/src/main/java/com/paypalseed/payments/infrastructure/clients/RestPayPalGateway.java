package com.paypalseed.payments.infrastructure.clients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paypalseed.payments.application.ports.PayPalException;
import com.paypalseed.payments.application.ports.PayPalException.Kind;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.platform.kernel.Money;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * PayPal REST adapter (Subscriptions v1, Orders v2, Payments v2/v1, Notifications v1). Only the
 * payments module holds these credentials (INV-10).
 */
@Component
public class RestPayPalGateway implements PayPalGateway {

    private final RestClient http;
    private final ObjectMapper json;
    private final String clientId;
    private final String clientSecret;
    private final String webhookId;
    private final String planId;
    private final String brandName;
    private volatile String accessToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public RestPayPalGateway(ObjectMapper json,
                             @Value("${seed.paypal.base-url:https://api-m.sandbox.paypal.com}") String baseUrl,
                             @Value("${seed.paypal.client-id:}") String clientId,
                             @Value("${seed.paypal.client-secret:}") String clientSecret,
                             @Value("${seed.paypal.webhook-id:}") String webhookId,
                             @Value("${seed.paypal.loan-plan-id:}") String planId,
                             @Value("${seed.paypal.brand-name:PayPal Seed}") String brandName) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.json = json;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.webhookId = webhookId;
        this.planId = planId;
        this.brandName = brandName;
    }

    @Override
    public Subscription createSubscription(CreateSubscription r, String requestId) {
        ObjectNode body = json.createObjectNode()
                .put("plan_id", requirePlan())
                .put("quantity", String.valueOf(r.quantity()))
                .put("custom_id", r.customId())
                .put("start_time", DateTimeFormatter.ISO_INSTANT.format(r.startTime()));
        body.putObject("application_context")
                .put("brand_name", brandName).put("user_action", "SUBSCRIBE_NOW").put("shipping_preference", "NO_SHIPPING")
                .put("return_url", r.returnUrl()).put("cancel_url", r.cancelUrl());
        return subscription(post("/v1/billing/subscriptions", body, requestId));
    }

    @Override
    public Subscription getSubscription(String subscriptionId) {
        return subscription(get("/v1/billing/subscriptions/" + subscriptionId));
    }

    @Override
    public Revision reviseQuantity(String subscriptionId, long quantity, String requestId) {
        JsonNode res = post("/v1/billing/subscriptions/" + subscriptionId + "/revise",
                json.createObjectNode().put("quantity", String.valueOf(quantity)), requestId);
        return new Revision(link(res, "approve"));
    }

    @Override
    public void suspend(String subscriptionId, String reason, String requestId) {
        post("/v1/billing/subscriptions/" + subscriptionId + "/suspend", json.createObjectNode().put("reason", reason), requestId);
    }

    @Override
    public void activate(String subscriptionId, String reason, String requestId) {
        post("/v1/billing/subscriptions/" + subscriptionId + "/activate", json.createObjectNode().put("reason", reason), requestId);
    }

    @Override
    public void cancel(String subscriptionId, String reason, String requestId) {
        post("/v1/billing/subscriptions/" + subscriptionId + "/cancel", json.createObjectNode().put("reason", reason), requestId);
    }

    @Override
    public Capture captureOutstanding(String subscriptionId, long amountCents, String note, String requestId) {
        ObjectNode body = json.createObjectNode().put("note", note).put("capture_type", "OUTSTANDING_BALANCE");
        body.putObject("amount").put("currency_code", Money.CURRENCY).put("value", Money.ofCents(amountCents).toDecimalString());
        JsonNode res = post("/v1/billing/subscriptions/" + subscriptionId + "/capture", body, requestId);
        return new Capture(res.path("id").asText(), res.path("status").asText("COMPLETED"), amountCents, 0,
                instant(res.path("time")).orElse(Instant.now()));
    }

    @Override
    public List<SubscriptionTransaction> subscriptionTransactions(String subscriptionId, Instant from, Instant to) {
        JsonNode res = get("/v1/billing/subscriptions/" + subscriptionId + "/transactions?start_time="
                + DateTimeFormatter.ISO_INSTANT.format(from) + "&end_time=" + DateTimeFormatter.ISO_INSTANT.format(to));
        List<SubscriptionTransaction> out = new ArrayList<>();
        for (JsonNode t : res.path("transactions")) {
            JsonNode amounts = t.path("amount_with_breakdown");
            out.add(new SubscriptionTransaction(t.path("id").asText(), t.path("status").asText(),
                    cents(amounts.path("gross_amount")), cents(amounts.path("fee_amount")),
                    instant(t.path("time")).orElse(Instant.now())));
        }
        return out;
    }

    @Override
    public Order createOrder(CreateOrder r, String requestId) {
        ObjectNode body = json.createObjectNode().put("intent", "CAPTURE");
        ObjectNode unit = body.putArray("purchase_units").addObject()
                .put("custom_id", r.customId()).put("invoice_id", r.invoiceId()).put("description", r.description());
        unit.putObject("amount").put("currency_code", Money.CURRENCY).put("value", Money.ofCents(r.amountCents()).toDecimalString());
        body.putObject("payment_source").putObject("paypal").putObject("experience_context")
                .put("brand_name", brandName).put("shipping_preference", "NO_SHIPPING").put("user_action", "PAY_NOW");
        JsonNode res = post("/v2/checkout/orders", body, requestId);
        return new Order(res.path("id").asText(), res.path("status").asText(), link(res, "payer-action").or(() -> link(res, "approve")));
    }

    @Override
    public Capture captureOrder(String orderId, String requestId) {
        JsonNode res = post("/v2/checkout/orders/" + orderId + "/capture", json.createObjectNode(), requestId);
        JsonNode capture = res.path("purchase_units").path(0).path("payments").path("captures").path(0);
        return new Capture(capture.path("id").asText(), capture.path("status").asText(), cents(capture.path("amount")),
                cents(capture.path("seller_receivable_breakdown").path("paypal_fee")),
                instant(capture.path("create_time")).orElse(Instant.now()));
    }

    @Override
    public Refund refundCapture(String captureId, long amountCents, String invoiceId, String requestId) {
        ObjectNode body = json.createObjectNode().put("invoice_id", invoiceId).put("note_to_payer", "Seed returned money you overpaid");
        body.putObject("amount").put("currency_code", Money.CURRENCY).put("value", Money.ofCents(amountCents).toDecimalString());
        JsonNode res = post("/v2/payments/captures/" + captureId + "/refund", body, requestId);
        return new Refund(res.path("id").asText(), res.path("status").asText(), amountCents);
    }

    @Override
    public Refund refundSale(String saleId, long amountCents, String requestId) {
        ObjectNode body = json.createObjectNode();
        body.putObject("amount").put("total", Money.ofCents(amountCents).toDecimalString()).put("currency", Money.CURRENCY);
        JsonNode res = post("/v1/payments/sale/" + saleId + "/refund", body, requestId);
        return new Refund(res.path("id").asText(), res.path("state").asText(), amountCents);
    }

    @Override
    public boolean verifyWebhook(Map<String, String> headers, String rawBody) {
        if (webhookId.isBlank()) {
            return false; // never act on webhooks we cannot verify
        }
        try {
            ObjectNode body = json.createObjectNode()
                    .put("auth_algo", headers.get("paypal-auth-algo"))
                    .put("cert_url", headers.get("paypal-cert-url"))
                    .put("transmission_id", headers.get("paypal-transmission-id"))
                    .put("transmission_sig", headers.get("paypal-transmission-sig"))
                    .put("transmission_time", headers.get("paypal-transmission-time"))
                    .put("webhook_id", webhookId);
            body.set("webhook_event", json.readTree(rawBody));
            return "SUCCESS".equals(post("/v1/notifications/verify-webhook-signature", body, null)
                    .path("verification_status").asText());
        } catch (IOException e) {
            return false;
        }
    }

    private JsonNode get(String path) {
        return call(() -> http.get().uri(path).header("Authorization", "Bearer " + token())
                .accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class));
    }

    private JsonNode post(String path, JsonNode body, String requestId) {
        return call(() -> {
            RestClient.RequestBodySpec spec = http.post().uri(path)
                    .header("Authorization", "Bearer " + token())
                    .header("Prefer", "return=representation")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON);
            if (requestId != null) {
                spec = spec.header("PayPal-Request-Id", requestId);
            }
            JsonNode res = spec.body(body).retrieve().body(JsonNode.class);
            return res == null ? json.createObjectNode() : res; // 204 No Content on suspend/activate/cancel
        });
    }

    private interface Call {
        JsonNode run();
    }

    private JsonNode call(Call call) {
        try {
            return call.run();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                tokenExpiresAt = Instant.EPOCH; // refresh once, then report AUTH if it still fails
                try {
                    return call.run();
                } catch (RestClientResponseException again) {
                    throw translate(again);
                }
            }
            throw translate(e);
        } catch (ResourceAccessException e) {
            Kind kind = e.getCause() instanceof SocketTimeoutException || e.getMessage().contains("timed out")
                    ? Kind.AMBIGUOUS : Kind.RETRYABLE;
            throw new PayPalException(kind, 0, null, "PayPal unreachable: " + e.getMessage(), e);
        }
    }

    private PayPalException translate(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        String issue = null;
        try {
            JsonNode err = json.readTree(e.getResponseBodyAsString());
            issue = err.path("details").path(0).path("issue").asText(err.path("name").asText(null));
        } catch (IOException ignored) {
            // body was not JSON; the status alone classifies the failure
        }
        return new PayPalException(PayPalException.classify(status.value()), status.value(), issue,
                "PayPal returned " + status.value() + (issue == null ? "" : " " + issue), e);
    }

    private synchronized String token() {
        if (accessToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return accessToken;
        }
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new PayPalException(Kind.AUTH, 0, null, "seed.paypal.client-id / client-secret are not configured");
        }
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        JsonNode res = http.post().uri("/v1/oauth2/token")
                .header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials")
                .retrieve().body(JsonNode.class);
        accessToken = res.path("access_token").asText();
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(60, res.path("expires_in").asLong(300) - 60));
        return accessToken;
    }

    private String requirePlan() {
        if (planId.isBlank()) {
            throw new PayPalException(Kind.FINAL, 0, null, "seed.paypal.loan-plan-id is not configured (run scripts/paypal-bootstrap.sh)");
        }
        return planId;
    }

    private Subscription subscription(JsonNode s) {
        return new Subscription(s.path("id").asText(), s.path("status").asText(), s.path("quantity").asLong(1),
                instant(s.path("billing_info").path("next_billing_time")), link(s, "approve"),
                Optional.ofNullable(s.path("custom_id").asText(null)));
    }

    private static Optional<String> link(JsonNode res, String rel) {
        for (JsonNode l : res.path("links")) {
            if (rel.equals(l.path("rel").asText())) {
                return Optional.of(l.path("href").asText());
            }
        }
        return Optional.empty();
    }

    private static Optional<Instant> instant(JsonNode node) {
        return node.isMissingNode() || node.isNull() || node.asText().isBlank()
                ? Optional.empty() : Optional.of(Instant.parse(node.asText()));
    }

    private static long cents(JsonNode amount) {
        return amount.isMissingNode() || amount.path("value").isMissingNode()
                ? 0 : Money.parse(amount.path("value").asText()).cents();
    }
}
