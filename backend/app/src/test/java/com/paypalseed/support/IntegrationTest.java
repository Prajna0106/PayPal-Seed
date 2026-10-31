package com.paypalseed.support;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.RegisterDisbursedLoan;
import com.paypalseed.lending.application.usecases.LoanQueries;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.usecases.InboxProcessor;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.testing.EmbeddedPostgresSupport;
import com.paypalseed.platform.testing.FakeBusinessClock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Boots the whole application (every module, real Postgres 16, real outbox) with a fake PayPal
 * and a settable business clock. Each test class gets its own database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(IntegrationTest.Fakes.class)
public abstract class IntegrationTest {

    public static final String SERVICE_TOKEN = "Bearer test-internal-token-0123456789";

    @TestConfiguration(proxyBeanMethods = false)
    public static class Fakes {
        @Bean
        @Primary
        FakeBusinessClock fakeBusinessClock() {
            return new FakeBusinessClock(LocalDate.of(2026, 11, 10));
        }

        @Bean
        @Primary
        FakePayPal fakePayPal() {
            return new FakePayPal();
        }
    }

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static volatile String databaseUrl;

    /** A fresh database for each test class (the context is discarded after the class). */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationTest::databaseUrl);
    }

    private static synchronized String databaseUrl() {
        if (databaseUrl == null) {
            databaseUrl = EmbeddedPostgresSupport.database("it_" + DATABASES.incrementAndGet());
        }
        return databaseUrl;
    }

    @AfterAll
    static void forgetDatabase() {
        databaseUrl = null;
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcClient jdbc;
    @Autowired
    protected FakeBusinessClock clock;
    @Autowired
    protected FakePayPal paypal;
    @Autowired
    protected LendingFacade lending;
    @Autowired
    protected LoanQueries queries;
    @Autowired
    protected LedgerFacade ledger;
    @Autowired
    protected InboxProcessor inbox;

    @BeforeEach
    void resetFakes() {
        paypal.reset();
    }

    /** Registers a disbursed loan of {@code dollars} over {@code months}, due on {@code dueDay}. */
    protected LoanSummary disburse(UUID memberId, long dollars, int months, int dueDay, LocalDate on) {
        clock.set(on);
        return lending.registerDisbursedLoan(new RegisterDisbursedLoan(memberId, dollars * 100, months, dueDay, on,
                "payout-" + Ids.newId()));
    }

    /** Lets every asynchronous consequence finish: inbox drained, no incomplete event publication left. */
    protected void settle() {
        try {
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until(() -> {
                inbox.drain(500);
                Integer incomplete = jdbc.sql("SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL")
                        .query(Integer.class).single();
                return incomplete == 0 && inbox.drain(500) == 0;
            });
        } catch (ConditionTimeoutException e) {
            List<String> stuck = jdbc.sql("""
                    SELECT listener_id || ' <- ' || event_type FROM event_publication
                     WHERE completion_date IS NULL GROUP BY 1 ORDER BY 1 LIMIT 20
                    """).query(String.class).list();
            throw new AssertionError("Events never completed (a listener keeps failing): " + stuck, e);
        }
    }

    protected long receivable(UUID loanId) {
        return ledger.balanceCents(AccountRef.of(AccountType.LOANS_RECEIVABLE, loanId));
    }

    protected long programCash() {
        return ledger.balanceCents(AccountRef.program(AccountType.PROGRAM_CASH));
    }

    protected void assertIntegrity(UUID loanId) {
        var integrity = queries.integrity(loanId);
        if (!integrity.consistent()) {
            throw new AssertionError("ledger and loan state disagree: " + integrity);
        }
    }

    /** Posts a PayPal webhook to the ingress endpoint, as PayPal would. */
    protected void webhook(String json) throws Exception {
        mvc.perform(post("/api/v1/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(json)
                        .header("paypal-transmission-sig", "ok"))
                .andExpect(status().isOk());
    }

    protected PayPalGateway gateway() {
        return paypal;
    }

    protected BusinessClock businessClock() {
        return clock;
    }
}
