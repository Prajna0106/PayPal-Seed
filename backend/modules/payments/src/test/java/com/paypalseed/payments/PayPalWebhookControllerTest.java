package com.paypalseed.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.infrastructure.persistence.InboxRepository;
import com.paypalseed.payments.infrastructure.web.PayPalWebhookController;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.SavingsFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PayPalWebhookControllerTest {

    private MockMvc mockMvc;
    private TestInboxRepository inbox;
    private RecordingSavingsFacade savingsFacade;
    private ObjectMapper objectMapper;

    static class TestInboxRepository extends InboxRepository {
        final Set<String> insertedIds = new HashSet<>();

        TestInboxRepository() {
            super(null);
        }

        @Override
        public boolean insert(String paypalEventId, String eventType, String payloadJson) {
            return insertedIds.add(paypalEventId);
        }
    }

    @ControllerAdvice
    static class TestControllerAdvice {
        @ExceptionHandler(DomainException.class)
        public ResponseEntity<Map<String, String>> handleDomain(DomainException ex) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", ex.getMessage()));
        }
    }

    @BeforeEach
    void setUp() {
        inbox = new TestInboxRepository();
        savingsFacade = new RecordingSavingsFacade();
        objectMapper = new ObjectMapper();

        PayPalGateway paypal = (PayPalGateway) Proxy.newProxyInstance(
            PayPalGateway.class.getClassLoader(),
            new Class<?>[]{PayPalGateway.class},
            (proxy, method, args) -> {
                if ("verifyWebhook".equals(method.getName())) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> headers = (Map<String, String>) args[0];
                    return !"bad".equals(headers.get("paypal-transmission-sig")) &&
                           headers.containsKey("paypal-transmission-id");
                }
                return null;
            }
        );

        PayPalWebhookController controller = new PayPalWebhookController(
            paypal,
            inbox,
            objectMapper,
            savingsFacade
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new TestControllerAdvice())
            .build();
    }

    @Test
    void processesPaymentSaleCompletedWebhookSuccessfully() throws Exception {
        String payload = """
        {
          "id": "WH-SALE-001",
          "event_type": "PAYMENT.SALE.COMPLETED",
          "resource": {
            "id": "CAPTURE-123",
            "billing_agreement_id": "I-ANA-BAKER-SUB",
            "amount": {
              "total": "75.00",
              "currency": "USD"
            }
          }
        }
        """;

        mockMvc.perform(post("/api/v1/payments/webhooks")
                .header("paypal-transmission-id", "TRANS-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("accepted"))
            .andExpect(jsonPath("$.eventId").value("WH-SALE-001"));

        assertEquals(1, savingsFacade.deposits.size());
        RecordingSavingsFacade.DepositRecord deposit = savingsFacade.deposits.get(0);
        assertEquals("I-ANA-BAKER-SUB", deposit.subscriptionId());
        assertEquals(Money.usd(BigDecimal.valueOf(75.00)), deposit.amount());
        assertEquals("CAPTURE-123", deposit.captureId());

        assertTrue(inbox.insertedIds.contains("WH-SALE-001"));
    }

    @Test
    void ignoresDuplicateWebhookGracefullyForIdempotency() throws Exception {
        String payload = """
        {
          "id": "WH-DUPLICATE-002",
          "event_type": "PAYMENT.SALE.COMPLETED",
          "resource": {
            "id": "CAPTURE-456",
            "billing_agreement_id": "I-ANA-BAKER-SUB",
            "amount": {
              "total": "75.00",
              "currency": "USD"
            }
          }
        }
        """;

        // First delivery: processes successfully
        mockMvc.perform(post("/api/v1/payments/webhooks")
                .header("paypal-transmission-id", "TRANS-002")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("accepted"));

        assertEquals(1, savingsFacade.deposits.size());

        // Second delivery (duplicate retry from PayPal): returns duplicate and does NOT call facade
        mockMvc.perform(post("/api/v1/payments/webhooks")
                .header("paypal-transmission-id", "TRANS-002")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("duplicate"))
            .andExpect(jsonPath("$.eventId").value("WH-DUPLICATE-002"));

        assertEquals(1, savingsFacade.deposits.size(), "Duplicate webhook must not double-record deposit");
    }

    @Test
    void pausesGoalOnSubscriptionCancelledWebhook() throws Exception {
        String payload = """
        {
          "id": "WH-CANCEL-003",
          "event_type": "BILLING.SUBSCRIPTION.CANCELLED",
          "resource": {
            "id": "I-CANCEL-SUB-999"
          }
        }
        """;

        mockMvc.perform(post("/api/v1/payments/webhooks")
                .header("paypal-transmission-id", "TRANS-003")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("accepted"));

        assertEquals(1, savingsFacade.pausedSubscriptions.size());
        assertEquals("I-CANCEL-SUB-999", savingsFacade.pausedSubscriptions.get(0));
    }

    @Test
    void rejectsWebhookWhenTransmissionHeaderMissing() throws Exception {
        String payload = """
        {
          "id": "WH-NO-AUTH-004",
          "event_type": "PAYMENT.SALE.COMPLETED"
        }
        """;

        mockMvc.perform(post("/api/v1/payments/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isUnprocessableEntity());

        assertEquals(0, savingsFacade.deposits.size());
    }

    static class RecordingSavingsFacade implements SavingsFacade {
        record DepositRecord(String subscriptionId, Money amount, String captureId) {}

        final List<DepositRecord> deposits = new ArrayList<>();
        final List<String> pausedSubscriptions = new ArrayList<>();

        @Override
        public void recordDepositBySubscription(String subscriptionId, Money amount, String payPalCaptureId) {
            deposits.add(new DepositRecord(subscriptionId, amount, payPalCaptureId));
        }

        @Override
        public void pauseGoalBySubscription(String subscriptionId) {
            pausedSubscriptions.add(subscriptionId);
        }
    }
}
