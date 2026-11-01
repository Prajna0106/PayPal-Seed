package com.paypalseed.agentgateway.application.usecases;

import com.paypalseed.agentgateway.api.KillSwitch;
import com.paypalseed.agentgateway.domain.model.TripRules;
import com.paypalseed.agentgateway.infrastructure.clients.LlmProperties;
import com.paypalseed.agentgateway.infrastructure.persistence.KillSwitchRepository;
import com.paypalseed.agentgateway.infrastructure.persistence.KillSwitchRepository.Row;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The mode store and the automatic trips. Each change commits on its own (REQUIRES_NEW), so a trip
 * raised inside a caller's transaction survives that caller rolling back. Effective modes are cached for
 * five seconds per agent; a change clears the cache in this process at once.
 *
 * <p>Trip evidence (the last 50 verdicts, the last hour of grounding failures) is kept per process: a
 * second instance trips on its own evidence, which only ever makes the platform safer.
 */
@Service
public class KillSwitchService implements KillSwitch {

    private static final Logger log = LoggerFactory.getLogger(KillSwitchService.class);
    private static final Duration CACHE_FOR = Duration.ofSeconds(5);
    private static final Pattern SCOPE = Pattern.compile("GLOBAL|[a-z][a-z0-9-]{1,40}");

    private record Cached(State state, Instant until) {
    }

    private final KillSwitchRepository repository;
    private final LlmProperties properties;
    private final ModelCircuits circuits;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;
    private final Instant startedAt;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, TripRules> rules = new ConcurrentHashMap<>();

    public KillSwitchService(KillSwitchRepository repository, LlmProperties properties, ModelCircuits circuits,
                             PlatformTransactionManager transactions) {
        this.repository = repository;
        this.properties = properties;
        this.circuits = circuits;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = Clock.systemUTC();
        this.startedAt = clock.instant();
    }

    @Override
    public Mode mode(String agent) {
        return state(agent).mode();
    }

    /** The stored mode is cached for five seconds; circuit states are always read live. */
    @Override
    public State state(String agent) {
        Instant now = clock.instant();
        Cached cached = cache.get(agent);
        State stored;
        if (cached != null && now.isBefore(cached.until())) {
            stored = cached.state();
        } else {
            stored = stored(agent);
            cache.put(agent, new Cached(stored, now.plus(CACHE_FOR)));
        }
        return new State(stored.mode(), stored.since(), stored.reason(), stored.trippedBy(), circuits.states(properties.models()));
    }

    private State stored(String agent) {
        List<Row> rows = repository.find(List.of(GLOBAL, agent));
        State state = rows.stream()
                .max(Comparator.comparing((Row r) -> Mode.valueOf(r.mode())).thenComparing(Row::changedAt))
                .map(r -> new State(Mode.valueOf(r.mode()), r.changedAt(), r.reason(), TrippedBy.valueOf(r.trippedBy()), Map.of()))
                .orElseGet(() -> new State(properties.defaultMode(), startedAt, null, null, Map.of()));
        if (properties.defaultMode().moreRestrictiveThan(state.mode())) {
            return new State(properties.defaultMode(), state.since(), "Configured default mode", null, Map.of());
        }
        return state;
    }

    /** The mode stored for exactly this scope (not the effective one), used for the who-may-raise rule. */
    public Mode scopeMode(String scope) {
        return repository.find(List.of(scope)).stream().findFirst().map(r -> Mode.valueOf(r.mode()))
                .orElse(properties.defaultMode());
    }

    @Override
    public void set(String scope, Mode mode, String reason, String changedBy) {
        requireScope(scope);
        Objects.requireNonNull(mode, "mode");
        change(scope, mode, reason, TrippedBy.OFFICER, changedBy);
        TripRules evidence = rules.get(scope);
        if (evidence != null && !mode.equals(Mode.TEMPLATES_ONLY)) {
            evidence.clear();
        }
    }

    @Override
    public void recordValidatorOutcome(String agent, boolean rejected) {
        if (rules(agent).recordVerdict(rejected)) {
            trip(agent, TrippedBy.AUTO_VALIDATOR_REJECTIONS, "Validator rejected more than 20% of recent proposals");
        }
    }

    @Override
    public void recordGroundingFailure(String agent) {
        if (rules(agent).recordGroundingFailure(clock.instant())) {
            trip(agent, TrippedBy.AUTO_GROUNDING_FAILURES, "Grounding failures exceeded 3 in one hour");
        }
    }

    /** The day's model budget is spent: every agent drops to templates until a person resets it. */
    public void tripBudget() {
        trip(GLOBAL, TrippedBy.AUTO_BUDGET, "Daily model budget spent");
    }

    /** Automatic trips only ever move towards TEMPLATES_ONLY and never repeat once there. */
    private void trip(String scope, TrippedBy cause, String reason) {
        if (scopeMode(scope) == Mode.TEMPLATES_ONLY) {
            return;
        }
        change(scope, Mode.TEMPLATES_ONLY, reason, cause, "system");
        log.warn("Kill switch tripped for {} by {}: {}", scope, cause, reason);
    }

    private void change(String scope, Mode mode, String reason, TrippedBy cause, String changedBy) {
        ownTransaction.executeWithoutResult(status -> {
            String from = repository.lockMode(scope).orElse(properties.defaultMode().name());
            repository.upsert(scope, mode.name(), reason, cause.name(), changedBy);
            repository.appendEvent(scope, from, mode.name(), reason, cause.name(), changedBy);
        });
        cache.clear();
    }

    private TripRules rules(String agent) {
        return rules.computeIfAbsent(agent, a -> new TripRules());
    }

    private static void requireScope(String scope) {
        if (scope == null || !SCOPE.matcher(scope).matches()) {
            throw new IllegalArgumentException("scope must be GLOBAL or an agent name");
        }
    }
}
