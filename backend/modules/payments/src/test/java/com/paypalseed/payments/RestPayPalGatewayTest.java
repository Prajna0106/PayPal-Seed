package com.paypalseed.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.api.dto.PaymentsDtos.FundingSource;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutWallet;
import com.paypalseed.payments.api.dto.PaymentsDtos.RecipientType;
import com.paypalseed.payments.application.ports.PayPalException;
import com.paypalseed.payments.application.ports.PayPalException.Kind;
import com.paypalseed.payments.application.ports.PayPalGateway.CreateOrder;
import com.paypalseed.payments.application.ports.PayPalGateway.CreatePayout;
import com.paypalseed.payments.application.ports.PayPalGateway.CreatePlan;
import com.paypalseed.payments.application.ports.PayPalGateway.CreateSubscription;
import com.paypalseed.payments.application.ports.PayPalGateway.PayoutItem;
import com.paypalseed.payments.application.ports.PayPalGateway.SearchTransactions;
import com.paypalseed.payments.application.ports.PayPalGateway.SearchedTransaction;
import com.paypalseed.payments.infrastructure.clients.RestPayPalGateway;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The REST adapter against a local HTTP server that plays PayPal: the exact request each call sends
 * (path, headers, body), how responses map, and how failures are classified.
 */
class RestPayPalGatewayTest {

    private record Reply(int status, String body, long delayMillis) {
    }

    private record Recorded(String method, String target, Map<String, String> headers, String body) {
        JsonNode json() throws IOException {
            return new ObjectMapper().readTree(body);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Deque<Reply>> replies = new ConcurrentHashMap<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private RestPayPalGateway paypal;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String target = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(exchange.getRequestMethod(), target, headers, body));
            Reply reply = next(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
            try {
                if (reply.delayMillis() > 0) {
                    Thread.sleep(reply.delayMillis());
                }
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            } catch (IOException | InterruptedException clientGaveUp) {
                // the timeout test closes the connection while this handler is still sleeping
            } finally {
                exchange.close();
            }
        });
        server.start();
        paypal = gateway(20_000);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private RestPayPalGateway gateway(long readTimeoutMillis) {
        return new RestPayPalGateway(mapper, "http://127.0.0.1:" + server.getAddress().getPort(), "client-1", "secret-1",
                "WH-1", "P-LOAN", "Seed Test", readTimeoutMillis);
    }

    private Reply next(String key) {
        if (key.equals("POST /v1/oauth2/token")) {
            return new Reply(200, "{\"access_token\":\"tok\",\"expires_in\":3600}", 0);
        }
        Deque<Reply> queue = replies.get(key);
        if (queue == null || queue.isEmpty()) {
            return new Reply(404, "{\"name\":\"RESOURCE_NOT_FOUND\"}", 0);
        }
        return queue.size() == 1 ? queue.peek() : queue.poll();
    }

    private void reply(String methodAndPath, int status, String body) {
        replies.computeIfAbsent(methodAndPath, k -> new ArrayDeque<>()).add(new Reply(status, body, 0));
    }

    private Recorded only(String method, String pathPrefix) {
        List<Recorded> hits = requests.stream().filter(r -> r.method().equals(method) && r.target().startsWith(pathPrefix)).toList();
        assertThat(hits).as(method + " " + pathPrefix).hasSize(1);
        return hits.get(0);
    }

    // ---- billing catalog ----

    @Test
    void createProductSendsTheCatalogBodyAndReadsTheId() throws Exception {
        reply("POST /v1/catalogs/products", 201, "{\"id\":\"PROD-1\",\"name\":\"PayPal Seed\"}");

        var product = paypal.createProduct("PayPal Seed", "Savings goals", "catalog-product:PayPal Seed");

        assertThat(product.id()).isEqualTo("PROD-1");
        Recorded sent = only("POST", "/v1/catalogs/products");
        assertThat(sent.headers()).containsEntry("authorization", "Bearer tok")
                .containsEntry("paypal-request-id", "catalog-product:PayPal Seed");
        assertThat(sent.json().path("name").asText()).isEqualTo("PayPal Seed");
        assertThat(sent.json().path("type").asText()).isEqualTo("SERVICE");
        assertThat(sent.json().path("category").asText()).isEqualTo("SOFTWARE");
    }

    @Test
    void listProductsFollowsPages() {
        reply("GET /v1/catalogs/products", 200, "{\"products\":[{\"id\":\"PROD-1\",\"name\":\"A\"}],\"total_pages\":2}");
        reply("GET /v1/catalogs/products", 200, "{\"products\":[{\"id\":\"PROD-2\",\"name\":\"B\"}],\"total_pages\":2}");

        var products = paypal.listProducts();

        assertThat(products).extracting("id").containsExactly("PROD-1", "PROD-2");
        assertThat(requests.stream().filter(r -> r.method().equals("GET")).map(Recorded::target).toList())
                .containsExactly("/v1/catalogs/products?page_size=20&page=1&total_required=true",
                        "/v1/catalogs/products?page_size=20&page=2&total_required=true");
    }

    @Test
    void createPlanIsAMonthlyPerUnitPlanWithQuantity() throws Exception {
        reply("POST /v1/billing/plans", 201,
                "{\"id\":\"P-1\",\"product_id\":\"PROD-1\",\"name\":\"seed-savings\",\"status\":\"ACTIVE\"}");

        var plan = paypal.createPlan(new CreatePlan("PROD-1", "seed-savings", "Monthly deposit", 100), "catalog-plan:1");

        assertThat(plan.id()).isEqualTo("P-1");
        assertThat(plan.status()).isEqualTo("ACTIVE");
        JsonNode body = only("POST", "/v1/billing/plans").json();
        assertThat(body.path("product_id").asText()).isEqualTo("PROD-1");
        assertThat(body.path("quantity_supported").asBoolean()).isTrue();
        JsonNode cycle = body.path("billing_cycles").path(0);
        assertThat(cycle.path("frequency").path("interval_unit").asText()).isEqualTo("MONTH");
        assertThat(cycle.path("total_cycles").asInt()).isZero();
        assertThat(cycle.path("pricing_scheme").path("fixed_price").path("value").asText()).isEqualTo("1.00");
        assertThat(cycle.path("pricing_scheme").path("fixed_price").path("currency_code").asText()).isEqualTo("USD");
        assertThat(body.path("payment_preferences").path("auto_bill_outstanding").asBoolean()).isFalse();
    }

    @Test
    void listPlansReadsNameAndStatus() {
        reply("GET /v1/billing/plans", 200,
                "{\"plans\":[{\"id\":\"P-1\",\"product_id\":\"PROD-1\",\"name\":\"a\",\"status\":\"INACTIVE\"},"
                        + "{\"id\":\"P-2\",\"name\":\"b\",\"status\":\"ACTIVE\"}],\"total_pages\":1}");

        var plans = paypal.listPlans("PROD-1");

        assertThat(plans).extracting("id", "name", "status", "productId")
                .containsExactly(tuple("P-1", "a", "INACTIVE", "PROD-1"),
                        tuple("P-2", "b", "ACTIVE", "PROD-1"));
        assertThat(requests.get(requests.size() - 1).target()).startsWith("/v1/billing/plans?product_id=PROD-1&");
    }

    // ---- subscriptions and orders ----

    @Test
    void savingsSubscriptionUsesThePlanQuantityAndCustomIdAndStartsOnApproval() throws Exception {
        reply("POST /v1/billing/subscriptions", 201, "{\"id\":\"I-1\",\"status\":\"APPROVAL_PENDING\",\"links\":"
                + "[{\"rel\":\"approve\",\"href\":\"https://paypal.test/approve\"}]}");

        var sub = paypal.createSubscriptionOnPlan("P-SAVE",
                new CreateSubscription(25, "goal:abc", null, "https://seed.test/ok", "https://seed.test/no"), "ss:1");

        assertThat(sub.id()).isEqualTo("I-1");
        assertThat(sub.approveUrl()).contains("https://paypal.test/approve");
        Recorded sent = only("POST", "/v1/billing/subscriptions");
        assertThat(sent.headers()).containsEntry("paypal-request-id", "ss:1");
        JsonNode body = sent.json();
        assertThat(body.path("plan_id").asText()).isEqualTo("P-SAVE");
        assertThat(body.path("quantity").asText()).isEqualTo("25");
        assertThat(body.path("custom_id").asText()).isEqualTo("goal:abc");
        assertThat(body.has("start_time")).isFalse();
        assertThat(body.path("application_context").path("return_url").asText()).isEqualTo("https://seed.test/ok");
    }

    @Test
    void loanSubscriptionKeepsUsingTheLoanPlanAndStartTime() throws Exception {
        reply("POST /v1/billing/subscriptions", 201, "{\"id\":\"I-2\",\"status\":\"APPROVAL_PENDING\"}");

        paypal.createSubscription(new CreateSubscription(150, "loan:1", Instant.parse("2026-12-01T18:00:00Z"), "r", "c"), "k");

        JsonNode body = only("POST", "/v1/billing/subscriptions").json();
        assertThat(body.path("plan_id").asText()).isEqualTo("P-LOAN");
        assertThat(body.path("start_time").asText()).isEqualTo("2026-12-01T18:00:00Z");
    }

    @Test
    void venmoOrderUsesTheVenmoPaymentSource() throws Exception {
        reply("POST /v2/checkout/orders", 201, "{\"id\":\"O-1\",\"status\":\"PAYER_ACTION_REQUIRED\",\"links\":"
                + "[{\"rel\":\"payer-action\",\"href\":\"https://paypal.test/pay\"}]}");

        var order = paypal.createOrder(new CreateOrder(2550, "goal:1:TOPUP", "INV-1", "Top-up", FundingSource.VENMO), "o-1");

        assertThat(order.approveUrl()).contains("https://paypal.test/pay");
        JsonNode body = only("POST", "/v2/checkout/orders").json();
        assertThat(body.path("payment_source").has("venmo")).isTrue();
        assertThat(body.path("payment_source").has("paypal")).isFalse();
        assertThat(body.path("purchase_units").path(0).path("amount").path("value").asText()).isEqualTo("25.50");
    }

    @Test
    void ordinaryOrderStillUsesThePayPalPaymentSource() throws Exception {
        reply("POST /v2/checkout/orders", 201, "{\"id\":\"O-2\",\"status\":\"CREATED\"}");

        paypal.createOrder(new CreateOrder(1000, "loan:1", "INV-2", "Loan payment"), "o-2");

        assertThat(only("POST", "/v2/checkout/orders").json().path("payment_source").has("paypal")).isTrue();
    }

    // ---- payouts ----

    @Test
    void payoutRequestCarriesTheBatchHeaderOneItemAndTheWallet() throws Exception {
        reply("POST /v1/payments/payouts", 201,
                "{\"batch_header\":{\"payout_batch_id\":\"B-1\",\"batch_status\":\"PENDING\"},\"links\":[]}");
        reply("GET /v1/payments/payouts/B-1", 200, "{\"batch_header\":{\"payout_batch_id\":\"B-1\",\"batch_status\":\"PENDING\"},"
                + "\"items\":[{\"payout_item_id\":\"IT-1\",\"transaction_status\":\"PENDING\",\"payout_batch_id\":\"B-1\"}]}");

        PayoutItem item = paypal.createPayout(new CreatePayout("seed:ref-1", "ref-1", RecipientType.PHONE, "+15551234567",
                PayoutWallet.VENMO, 1234, "Your Seed loan"), "payout:ref-1");

        Recorded sent = only("POST", "/v1/payments/payouts");
        assertThat(sent.headers()).containsEntry("paypal-request-id", "payout:ref-1")
                .containsEntry("authorization", "Bearer tok");
        JsonNode body = sent.json();
        assertThat(body.path("sender_batch_header").path("sender_batch_id").asText()).isEqualTo("seed:ref-1");
        assertThat(body.path("items")).hasSize(1);
        JsonNode line = body.path("items").path(0);
        assertThat(line.path("recipient_type").asText()).isEqualTo("PHONE");
        assertThat(line.path("receiver").asText()).isEqualTo("+15551234567");
        assertThat(line.path("recipient_wallet").asText()).isEqualTo("VENMO");
        assertThat(line.path("sender_item_id").asText()).isEqualTo("ref-1");
        assertThat(line.path("note").asText()).isEqualTo("Your Seed loan");
        assertThat(line.path("amount").path("value").asText()).isEqualTo("12.34");
        assertThat(line.path("amount").path("currency").asText()).isEqualTo("USD");
        assertThat(item.batchId()).isEqualTo("B-1");
        assertThat(item.itemId()).contains("IT-1");
        assertThat(item.transactionStatus()).contains("PENDING");
    }

    @Test
    void payoutStillSucceedsWhenTheFollowUpBatchReadFails() {
        reply("POST /v1/payments/payouts", 201,
                "{\"batch_header\":{\"payout_batch_id\":\"B-2\",\"batch_status\":\"PENDING\"}}");
        reply("GET /v1/payments/payouts/B-2", 503, "{\"name\":\"INTERNAL_SERVICE_ERROR\"}");

        PayoutItem item = paypal.createPayout(new CreatePayout("seed:r", "r", RecipientType.EMAIL, "a@b.test",
                PayoutWallet.PAYPAL, 500, null), "payout:r");

        assertThat(item.batchId()).isEqualTo("B-2");
        assertThat(item.itemId()).isEmpty();
        assertThat(item.batchStatus()).isEqualTo("PENDING");
    }

    @Test
    void payoutItemReadMapsStatusFeeAndError() {
        reply("GET /v1/payments/payouts-item/IT-9", 200, "{\"payout_item_id\":\"IT-9\",\"payout_batch_id\":\"B-9\","
                + "\"transaction_id\":\"TX-9\",\"transaction_status\":\"FAILED\",\"payout_item_fee\":{\"currency\":\"USD\",\"value\":\"0.25\"},"
                + "\"errors\":{\"name\":\"RECEIVER_UNREGISTERED\",\"message\":\"nope\"}}");

        PayoutItem item = paypal.getPayoutItem("IT-9");

        assertThat(item.itemId()).contains("IT-9");
        assertThat(item.batchId()).isEqualTo("B-9");
        assertThat(item.transactionStatus()).contains("FAILED");
        assertThat(item.transactionId()).contains("TX-9");
        assertThat(item.feeCents()).isEqualTo(25);
        assertThat(item.errorName()).contains("RECEIVER_UNREGISTERED");
    }

    @Test
    void payoutMoneyConvertsToDecimalStringsExactly() throws Exception {
        reply("POST /v1/payments/payouts", 201, "{\"batch_header\":{\"payout_batch_id\":\"B-3\",\"batch_status\":\"SUCCESS\"},"
                + "\"items\":[{\"payout_item_id\":\"IT-3\",\"transaction_status\":\"SUCCESS\"}]}");

        paypal.createPayout(new CreatePayout("seed:c", "c", RecipientType.PAYPAL_ID, "PAYER1", PayoutWallet.PAYPAL, 1, null), "k");

        assertThat(only("POST", "/v1/payments/payouts").json().path("items").path(0).path("amount").path("value").asText())
                .isEqualTo("0.01");
    }

    // ---- transaction search ----

    @Test
    void searchSendsTheWindowFieldsAndPagesAndMapsAmounts() {
        reply("GET /v1/reporting/transactions", 200, "{\"transaction_details\":[{\"transaction_info\":{\"transaction_id\":\"T1\","
                + "\"transaction_event_code\":\"T0006\",\"transaction_status\":\"S\",\"transaction_initiation_date\":\"2026-10-03T04:03:52+0000\","
                + "\"transaction_amount\":{\"currency_code\":\"USD\",\"value\":\"465.00\"},\"fee_amount\":{\"currency_code\":\"USD\",\"value\":\"-13.79\"},"
                + "\"transaction_subject\":\"Gig\"},\"payer_info\":{\"account_id\":\"ACC1\",\"payer_name\":{\"given_name\":\"Ana\"}}},"
                + "{\"transaction_info\":{\"transaction_id\":\"T2\",\"transaction_event_code\":\"T0006\",\"transaction_status\":\"S\","
                + "\"transaction_initiation_date\":\"2026-10-04T00:00:00Z\",\"transaction_amount\":{\"currency_code\":\"EUR\",\"value\":\"9.00\"}}}],"
                + "\"total_pages\":2,\"page\":1}");
        reply("GET /v1/reporting/transactions", 200, "{\"transaction_details\":[{\"transaction_info\":{\"transaction_id\":\"T3\","
                + "\"transaction_event_code\":\"T1107\",\"transaction_status\":\"S\",\"paypal_reference_id\":\"T1\","
                + "\"transaction_initiation_date\":\"2026-10-05T10:00:00+0000\",\"transaction_amount\":{\"currency_code\":\"USD\",\"value\":\"-100.00\"}}}],"
                + "\"total_pages\":2,\"page\":2}");

        List<SearchedTransaction> found = paypal.searchTransactions(new SearchTransactions(
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-31T00:00:00Z"), Optional.empty()));

        assertThat(found).extracting(SearchedTransaction::transactionId).containsExactly("T1", "T3");
        SearchedTransaction first = found.get(0);
        assertThat(first.amountCents()).isEqualTo(46_500);
        assertThat(first.feeCents()).isEqualTo(-1_379);
        assertThat(first.initiatedAt()).isEqualTo(Instant.parse("2026-10-03T04:03:52Z"));
        assertThat(first.payerIsPerson()).isTrue();
        assertThat(first.payerAccountId()).contains("ACC1");
        assertThat(first.subject()).isEqualTo("Gig");
        assertThat(found.get(1).amountCents()).isEqualTo(-10_000);
        assertThat(found.get(1).referenceTransactionId()).contains("T1");
        List<String> targets = requests.stream().filter(r -> r.method().equals("GET")).map(Recorded::target).toList();
        assertThat(targets.get(0)).isEqualTo("/v1/reporting/transactions?start_date=2026-10-01T00:00:00Z&end_date=2026-10-31T00:00:00Z"
                + "&fields=transaction_info,payer_info&page_size=100&page=1");
        assertThat(targets.get(1)).endsWith("&page=2");
        assertThat(requests.stream().filter(r -> r.target().startsWith("/v1/reporting")).map(r -> r.headers().get("paypal-auth-assertion")))
                .containsOnlyNulls();
    }

    @Test
    void searchOnBehalfOfAnotherAccountSendsAnUnsignedAuthAssertion() throws Exception {
        reply("GET /v1/reporting/transactions", 200, "{\"transaction_details\":[],\"total_pages\":1}");

        paypal.searchTransactions(new SearchTransactions(Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-02T00:00:00Z"), Optional.of("MERCHANT123")));

        String assertion = only("GET", "/v1/reporting/transactions").headers().get("paypal-auth-assertion");
        String[] parts = assertion.split("\\.", -1);
        assertThat(parts).hasSize(3);
        assertThat(parts[2]).isEmpty();
        assertThat(new String(Base64.getDecoder().decode(parts[0]), StandardCharsets.UTF_8)).isEqualTo("{\"alg\":\"none\"}");
        JsonNode claims = mapper.readTree(Base64.getDecoder().decode(parts[1]));
        assertThat(claims.path("payer_id").asText()).isEqualTo("MERCHANT123");
        assertThat(claims.path("iss").asText()).isEqualTo("client-1");
    }

    @Test
    void searchRefusesAWindowOverThirtyOneDays() {
        assertThatThrownBy(() -> paypal.searchTransactions(new SearchTransactions(Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-02T00:00:01Z"), Optional.empty()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).isEmpty();
    }

    // ---- failure handling ----

    @Test
    void validationErrorIsFinalAndCarriesPayPalsIssue() {
        reply("POST /v1/payments/payouts", 400,
                "{\"name\":\"VALIDATION_ERROR\",\"details\":[{\"field\":\"items[0].receiver\",\"issue\":\"INVALID_RECEIVER\"}]}");

        assertThatThrownBy(() -> paypal.createPayout(new CreatePayout("seed:x", "x", RecipientType.EMAIL, "bad",
                PayoutWallet.PAYPAL, 100, null), "k")).isInstanceOfSatisfying(PayPalException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.FINAL);
                    assertThat(e.httpStatus()).isEqualTo(400);
                    assertThat(e.paypalIssue()).isEqualTo("INVALID_RECEIVER");
                });
    }

    @Test
    void unprocessableEntityIsAStateErrorAndServerErrorsAreRetryable() {
        reply("POST /v1/billing/plans", 422, "{\"name\":\"UNPROCESSABLE_ENTITY\",\"details\":[{\"issue\":\"PRODUCT_NOT_FOUND\"}]}");
        reply("POST /v1/catalogs/products", 503, "{\"name\":\"SERVICE_UNAVAILABLE\"}");
        reply("GET /v1/payments/payouts-item/I", 429, "{\"name\":\"RATE_LIMIT_REACHED\"}");

        assertThatThrownBy(() -> paypal.createPlan(new CreatePlan("P", "n", "d", 100), "k"))
                .isInstanceOfSatisfying(PayPalException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.STATE);
                    assertThat(e.paypalIssue()).isEqualTo("PRODUCT_NOT_FOUND");
                });
        assertThatThrownBy(() -> paypal.createProduct("n", "d", "k"))
                .isInstanceOfSatisfying(PayPalException.class, e -> assertThat(e.isTransient()).isTrue());
        assertThatThrownBy(() -> paypal.getPayoutItem("I"))
                .isInstanceOfSatisfying(PayPalException.class, e -> assertThat(e.kind()).isEqualTo(Kind.RETRYABLE));
    }

    @Test
    void anExpiredTokenIsRefreshedOnceAndTheCallRetried() {
        reply("GET /v1/payments/payouts-item/IT-1", 401, "{\"name\":\"AUTHENTICATION_FAILURE\"}");
        reply("GET /v1/payments/payouts-item/IT-1", 200, "{\"payout_item_id\":\"IT-1\",\"transaction_status\":\"SUCCESS\"}");

        PayoutItem item = paypal.getPayoutItem("IT-1");

        assertThat(item.transactionStatus()).contains("SUCCESS");
        assertThat(requests.stream().filter(r -> r.target().equals("/v1/oauth2/token")).count()).isEqualTo(2);
    }

    @Test
    void aSecond401IsReportedAsAuth() {
        reply("GET /v1/payments/payouts-item/IT-2", 401, "{\"name\":\"AUTHENTICATION_FAILURE\"}");

        assertThatThrownBy(() -> paypal.getPayoutItem("IT-2"))
                .isInstanceOfSatisfying(PayPalException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTH));
    }

    @Test
    void aReadTimeoutIsAmbiguousBecauseThePayoutMayHaveBeenAccepted() {
        replies.computeIfAbsent("POST /v1/payments/payouts", k -> new ArrayDeque<>())
                .add(new Reply(201, "{\"batch_header\":{\"payout_batch_id\":\"B\"}}", 1_500));
        RestPayPalGateway impatient = gateway(250);

        assertThatThrownBy(() -> impatient.createPayout(new CreatePayout("seed:t", "t", RecipientType.EMAIL, "a@b.test",
                PayoutWallet.PAYPAL, 100, null), "payout:t")).isInstanceOfSatisfying(PayPalException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.AMBIGUOUS);
                    assertThat(e.isTransient()).isTrue();
                });
    }

    @Test
    void anUnreachableServerIsRetryable() {
        server.stop(0);

        assertThatThrownBy(() -> paypal.getPayoutItem("IT-3"))
                .isInstanceOfSatisfying(PayPalException.class, e -> assertThat(e.isTransient()).isTrue());
    }
}
