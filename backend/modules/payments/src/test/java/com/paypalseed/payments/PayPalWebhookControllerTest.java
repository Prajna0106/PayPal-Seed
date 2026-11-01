package com.paypalseed.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.infrastructure.persistence.InboxRepository;
import com.paypalseed.payments.infrastructure.web.PayPalWebhookController;
import com.paypalseed.platform.kernel.DomainException;
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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PayPalWebhookControllerTest {

    private MockMvc mockMvc;
    private TestInboxRepository inbox;
    private ObjectMapper objectMapper;

    static class TestInboxRepository extends InboxRepository {
        final Set<String> insertedIds = new HashSet<>();
        final Map<String, String> typesById = new HashMap<>();

        TestInboxRepository() {
            super(null);
        }

        @Override
        public boolean insert(String paypalEventId, String eventType, String payloadJson) {
            typesById.putIfAbsent(paypalEventId, eventType);
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
            objectMapper
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

        // Ingress only stores the verified event; the inbox processor turns it into a savings deposit event.
        assertTrue(inbox.insertedIds.contains("WH-SALE-001"));
        assertEquals("PAYMENT.SALE.COMPLETED", inbox.typesById.get("WH-SALE-001"));
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

        assertEquals(1, inbox.insertedIds.size());

        // Second delivery (duplicate retry from PayPal): reported as a duplicate, stored once
        mockMvc.perform(post("/api/v1/payments/webhooks")
                .header("paypal-transmission-id", "TRANS-002")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("duplicate"))
            .andExpect(jsonPath("$.eventId").value("WH-DUPLICATE-002"));

        assertEquals(1, inbox.insertedIds.size(), "Duplicate webhook must be stored once");
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

        assertEquals("BILLING.SUBSCRIPTION.CANCELLED", inbox.typesById.get("WH-CANCEL-003"));
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

        assertEquals(0, inbox.insertedIds.size());
    }
}
