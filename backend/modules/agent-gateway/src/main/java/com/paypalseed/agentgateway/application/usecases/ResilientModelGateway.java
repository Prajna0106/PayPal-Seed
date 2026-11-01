package com.paypalseed.agentgateway.application.usecases;

import com.paypalseed.agentgateway.api.KillSwitch.Mode;
import com.paypalseed.agentgateway.api.ModelGateway;
import com.paypalseed.agentgateway.api.ModelUnavailableException;
import com.paypalseed.agentgateway.api.ModelUnavailableException.Reason;
import com.paypalseed.agentgateway.api.PiiRedactor;
import com.paypalseed.agentgateway.api.llm.LlmClient;
import com.paypalseed.agentgateway.api.llm.LlmException;
import com.paypalseed.agentgateway.api.llm.ModelRequest;
import com.paypalseed.agentgateway.api.llm.ModelResponse;
import com.paypalseed.agentgateway.infrastructure.clients.LlmProperties;
import com.paypalseed.agentgateway.infrastructure.persistence.UsageBudgetRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The guard rails around every model call, in order: kill switch, configuration, daily budget, circuit
 * breakers, bulkhead, PII redaction, then the call with one jittered retry. Every refusal is a
 * {@link ModelUnavailableException}; callers fall back to templates (04 §8).
 *
 * <p>Circuits: the chain sent to OpenRouter holds only models with a closed (or probing) circuit. When the
 * answer comes from a later model, the earlier ones are counted as failures; when every attempt fails,
 * every model in the chain is.
 */
@Service
public class ResilientModelGateway implements ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(ResilientModelGateway.class);
    static final Duration BULKHEAD_WAIT = Duration.ofSeconds(2);

    private final LlmClient client;
    private final LlmProperties properties;
    private final KillSwitchService killSwitch;
    private final ModelCircuits circuits;
    private final UsageBudgetRepository budget;
    private final BusinessClock clock;
    private final TransactionTemplate ownTransaction;
    private final Semaphore bulkhead;

    public ResilientModelGateway(LlmClient client, LlmProperties properties, KillSwitchService killSwitch,
                                 ModelCircuits circuits, UsageBudgetRepository budget, BusinessClock clock,
                                 PlatformTransactionManager transactions) {
        this.client = client;
        this.properties = properties;
        this.killSwitch = killSwitch;
        this.circuits = circuits;
        this.budget = budget;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.bulkhead = new Semaphore(properties.maxConcurrent(), true);
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        if (killSwitch.mode(request.agent()) == Mode.TEMPLATES_ONLY) {
            throw new ModelUnavailableException(Reason.DISABLED, "Kill switch is in TEMPLATES_ONLY for " + request.agent());
        }
        if (!properties.enabled()) {
            throw new ModelUnavailableException(Reason.NOT_CONFIGURED, "Model calls are disabled by configuration");
        }
        LocalDate day = clock.today();
        if (spent(day) >= properties.dailyBudgetMicroUsd()) {
            killSwitch.tripBudget();
            throw new ModelUnavailableException(Reason.BUDGET_EXHAUSTED, "Daily model budget is spent");
        }
        List<String> chain = circuits.allowed(properties.models());
        if (chain.isEmpty()) {
            throw new ModelUnavailableException(Reason.CIRCUIT_OPEN, "Every model circuit is open");
        }
        acquire();
        try {
            ModelResponse response = callWithRetry(redacted(request), chain);
            long total = record(day, response);
            if (total >= properties.dailyBudgetMicroUsd()) {
                killSwitch.tripBudget();
            }
            return response;
        } finally {
            bulkhead.release();
        }
    }

    private ModelResponse callWithRetry(ModelRequest request, List<String> chain) {
        LlmException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                ModelResponse response = client.complete(request, chain);
                creditCircuits(chain, response.model());
                return response;
            } catch (LlmException e) {
                last = e;
                if (e.kind() == LlmException.Kind.NOT_CONFIGURED) {
                    throw new ModelUnavailableException(Reason.NOT_CONFIGURED, e.getMessage(), e);
                }
                if (!e.retryable() || attempt == 2) {
                    break;
                }
                pause();
            }
        }
        chain.forEach(circuits::recordFailure);
        log.warn("Model call for {} ({}) failed after retry: status {}", request.agent(), request.purpose(), last.status());
        throw new ModelUnavailableException(Reason.FAILED, "Model provider failed", last);
    }

    /** The serving model succeeded; models listed before it were skipped by the provider, so they failed. */
    private void creditCircuits(List<String> chain, String served) {
        int index = 0;
        for (int i = 0; i < chain.size(); i++) {
            if (served != null && served.startsWith(chain.get(i))) {
                index = i;
                break;
            }
        }
        for (int i = 0; i < index; i++) {
            circuits.recordFailure(chain.get(i));
        }
        circuits.recordSuccess(chain.get(index));
    }

    /** Redacts every message and caps the timeout at {@code seed.llm.call-timeout}. */
    private ModelRequest redacted(ModelRequest request) {
        Duration timeout = request.timeout().compareTo(properties.callTimeout()) > 0 ? properties.callTimeout() : request.timeout();
        return request.withTimeout(timeout)
                .withMessages(request.messages().stream().map(m -> m.withContent(PiiRedactor.redact(m.content()))).toList());
    }

    private void acquire() {
        try {
            if (!bulkhead.tryAcquire(BULKHEAD_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ModelUnavailableException(Reason.BULKHEAD_FULL, "Too many concurrent model calls");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException(Reason.BULKHEAD_FULL, "Interrupted while waiting for a model slot", e);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(100, 400));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException(Reason.FAILED, "Interrupted before the retry", e);
        }
    }

    /** Usage is counted in its own transaction: a caller rolling back must not hide money already spent. */
    private long record(LocalDate day, ModelResponse response) {
        Long total = ownTransaction.execute(status ->
                budget.add(day, (long) response.tokensIn() + response.tokensOut(), response.costMicroUsd()));
        return total == null ? 0 : total;
    }

    private long spent(LocalDate day) {
        Long spent = ownTransaction.execute(status -> budget.costMicroUsd(day));
        return spent == null ? 0 : spent;
    }
}
