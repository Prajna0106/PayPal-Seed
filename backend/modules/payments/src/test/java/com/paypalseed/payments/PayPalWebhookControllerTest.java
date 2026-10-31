package com.paypalseed.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.payments.application.PayPalWebhookVerifier;
import com.paypalseed.payments.infrastructure.clients.DefaultPayPalWebhookVerifier;
import com.paypalseed.payments.infrastructure.persistence.InMemoryWebhookInbox;
import com.paypalseed.payments.infrastructure.web.PayPalWebhookController;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.SavingsFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PayPalWebhookControllerTest {

    private MockMvc mockMvc;
    private InMemoryWebhookInbox inbox;
    private PayPalWebhookVerifier verifier;
    private RecordingSavingsFacade savingsFacade;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        inbox = new InMemoryWebhookInbox();
        verifier = new DefaultPayPalWebhookVerifier();
        savingsFacade = new RecordingSavingsFacade();
        objectMapper = new ObjectMapper();

        PayPalWebhookController controller = new PayPalWebhookController(
            inbox,
            verifier,
            savingsFacade,
            objectMapper
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
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
                .header("PAYPAL-TRANSMISSION-ID", "TRANS-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.eventId").value("WH-SALE-001"));

        // Verify savings facade was invoked with exact parsed parameters
        assertEquals(1, savingsFacade.deposits.size());
        RecordingSavingsFacade.DepositRecord deposit = savingsFacade.deposits.get(0);
        assertEquals("I-ANA-BAKER-SUB", deposit.subscriptionId());
        assertEquals(Money.usd(BigDecimal.valueOf(75.00)), deposit.amount());
        assertEquals("CAPTURE-123", deposit.captureId());

        // Verify recorded in inbox
        assertTrue(inbox.isProcessed("WH-SALE-001"));
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
                .header("PAYPAL-TRANSMISSION-ID", "TRANS-002")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"));

        assertEquals(1, savingsFacade.deposits.size());

        // Second delivery (duplicate retry from PayPal): returns DUPLICATE_IGNORED and does NOT call facade
        mockMvc.perform(post("/api/v1/payments/webhooks")
                .header("PAYPAL-TRANSMISSION-ID", "TRANS-002")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DUPLICATE_IGNORED"))
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
                .header("PAYPAL-TRANSMISSION-ID", "TRANS-003")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"));

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
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Invalid webhook signature"));

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
