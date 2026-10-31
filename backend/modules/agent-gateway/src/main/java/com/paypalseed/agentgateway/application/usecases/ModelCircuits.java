package com.paypalseed.agentgateway.application.usecases;

import com.paypalseed.agentgateway.domain.model.CircuitBreaker;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * One circuit breaker per model (04 §10). While a model's circuit is open it is left out of the chain sent
 * to the provider, so a failing model costs nothing until its probe succeeds. State is per process.
 */
@Component
public class ModelCircuits {

    static final int FAILURE_THRESHOLD = 5;
    static final Duration OPEN_FOR = Duration.ofSeconds(60);

    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Clock clock;

    public ModelCircuits() {
        this(Clock.systemUTC());
    }

    ModelCircuits(Clock clock) {
        this.clock = clock;
    }

    /** The models of {@code chain} that may be called now, in order. Claims the half-open probe slot. */
    public List<String> allowed(List<String> chain) {
        return chain.stream().filter(model -> breaker(model).allowRequest()).toList();
    }

    public void recordSuccess(String model) {
        breaker(model).recordSuccess();
    }

    public void recordFailure(String model) {
        breaker(model).recordFailure();
    }

    public Map<String, String> states(List<String> chain) {
        Map<String, String> states = new LinkedHashMap<>();
        chain.forEach(model -> states.put(model, breaker(model).state().name()));
        return states;
    }

    private CircuitBreaker breaker(String model) {
        return breakers.computeIfAbsent(model, m -> new CircuitBreaker(FAILURE_THRESHOLD, OPEN_FOR, clock));
    }
}
