package com.paypalseed.fraud.application.usecases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.api.dto.FraudDtos.Observation;
import com.paypalseed.fraud.api.events.FraudEvents.FraudFlagRaised;
import com.paypalseed.fraud.domain.model.Fact;
import com.paypalseed.fraud.domain.model.FlagScore;
import com.paypalseed.fraud.domain.model.Signal;
import com.paypalseed.fraud.domain.rules.Rule;
import com.paypalseed.fraud.domain.rules.RuleContext;
import com.paypalseed.fraud.domain.rules.RuleSet;
import com.paypalseed.fraud.infrastructure.persistence.DestinationRepository;
import com.paypalseed.fraud.infrastructure.persistence.DestinationRepository.Change;
import com.paypalseed.fraud.infrastructure.persistence.FactRepository;
import com.paypalseed.fraud.infrastructure.persistence.FlagRepository;
import com.paypalseed.fraud.infrastructure.persistence.FlagRepository.FlagRow;
import com.paypalseed.fraud.infrastructure.persistence.SignalRepository;
import com.paypalseed.fraud.infrastructure.persistence.SignalRepository.StoredSignal;
import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.dto.GovernanceDtos.AuditCommand;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes in facts and runs the rules. Signals accumulate per member; when their score reaches the threshold an advisory
 * flag opens (once per member at a time). Nothing here blocks anyone: the flag is input for officers.
 */
@Service
public class FraudSignals {

    /** A payer shared by more members than this is evaluated for the first few only; the rest see it when they next act. */
    private static final int SHARING_FAN_OUT = 25;
    private static final Set<String> ATTRIBUTE_KEYS = Set.of("goalId", "loanId");
    private static final int MAX_ATTRIBUTE_VALUE = 64;

    private final FactRepository facts;
    private final DestinationRepository destinations;
    private final SignalRepository signals;
    private final FlagRepository flags;
    private final ReferenceHasher hasher;
    private final FraudSettings settings;
    private final GovernanceFacade governance;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final List<Rule> rules = RuleSet.v1();

    public FraudSignals(FactRepository facts, DestinationRepository destinations, SignalRepository signals, FlagRepository flags,
                        ReferenceHasher hasher, FraudSettings settings, GovernanceFacade governance, BusinessClock clock,
                        ApplicationEventPublisher events, ObjectMapper json) {
        this.facts = facts;
        this.destinations = destinations;
        this.signals = signals;
        this.flags = flags;
        this.hasher = hasher;
        this.settings = settings;
        this.governance = governance;
        this.clock = clock;
        this.events = events;
        this.json = json;
    }

    /** Records the fact (once per source key) and re-evaluates every member it can change the picture for. */
    @Transactional
    public void observe(Observation o) {
        String refHash = hasher.hash(o.reference());
        if (o.kind() == FactKind.PAYER_SEEN && refHash != null) {
            // Two members first seen with one payer at the same moment must not each miss the other: observations of a
            // payer queue here, so the second one sees the first one's committed fact.
            facts.lock("payer:" + refHash);
        }
        Optional<Long> inserted = facts.insert(o.memberId(), o.kind(), o.occurredAt(), o.sourceKey(), o.amountCents(), refHash,
                allowed(o.attributes()));
        if (inserted.isEmpty()) {
            return;
        }
        // A payer seen for a second member changes the first member's picture too.
        Set<UUID> affected = new TreeSet<>(List.of(o.memberId()));
        if (o.kind() == FactKind.PAYER_SEEN && refHash != null) {
            affected.addAll(facts.membersSharing(Set.of(refHash), o.memberId(), SHARING_FAN_OUT));
        }
        // Locks are taken in id order up front, so two overlapping evaluations cannot wait on each other.
        affected.forEach(member -> facts.lock("member:" + member));
        affected.forEach(this::evaluate);
    }

    /** Tells a real change of payout destination from a profile update that left it alone; only the former is a fact. */
    @Transactional
    public void payoutDestination(UUID memberId, String reference, Instant occurredAt, String sourceKey) {
        String refHash = hasher.hash(reference);
        if (refHash == null) {
            return;
        }
        // The member's lock first (as observe takes it), so a destination and a fact of one member never wait on each other.
        facts.lock("member:" + memberId);
        if (destinations.record(memberId, refHash, occurredAt) == Change.CHANGED) {
            observe(new Observation(memberId, FactKind.PAYOUT_RECIPIENT_CHANGED, occurredAt, sourceKey, null, reference, Map.of()));
        }
    }

    private void evaluate(UUID memberId) {
        Instant now = clock.now();
        List<Fact> history = facts.factsOf(memberId, now.minus(settings.lookback()));
        Set<String> refs = new HashSet<>();
        history.stream().filter(f -> f.kind() == FactKind.PAYER_SEEN && f.refHash() != null).forEach(f -> refs.add(f.refHash()));
        RuleContext context = new RuleContext(memberId, now, history, facts.otherMembersByRef(refs, memberId));

        boolean anyNew = false;
        for (Rule rule : rules) {
            Optional<Signal> fired = rule.evaluate(context);
            if (fired.isPresent() && signals.insertIfAbsent(memberId, fired.get())) {
                anyNew = true;
            }
        }
        if (!anyNew) {
            return;
        }
        List<StoredSignal> pending = signals.unattached(memberId);
        Optional<FlagRow> open = flags.openFor(memberId);
        List<Signal> counted = new ArrayList<>(pending.stream().map(StoredSignal::signal).toList());
        open.ifPresent(f -> signals.ofFlag(f.id()).forEach(s -> counted.add(s.signal())));
        int score = FlagScore.of(counted);
        List<Long> pendingIds = pending.stream().map(StoredSignal::id).toList();

        if (open.isPresent()) {
            signals.attach(open.get().id(), pendingIds);
            flags.setScore(open.get().id(), score);
        } else if (score >= settings.flagThreshold()) {
            UUID flagId = Ids.newId();
            flags.insert(flagId, memberId, score, now);
            signals.attach(flagId, pendingIds);
            List<String> codes = counted.stream().map(Signal::code).distinct().sorted().toList();
            events.publishEvent(new FraudFlagRaised(Ids.newId(), flagId, memberId, score, codes, now));
            governance.audit(new AuditCommand("FRAUD_FLAG_RAISED", flagId, "SYSTEM", "fraud-rules",
                    audit(Map.of("flagId", flagId, "memberId", memberId, "score", score, "signals", codes, "rules", RuleSet.VERSION))));
        }
    }

    /** Only the allow-listed keys, with short values: a caller cannot slip personal data into a fact. */
    private static Map<String, String> allowed(Map<String, String> attributes) {
        Map<String, String> kept = new LinkedHashMap<>();
        attributes.forEach((k, v) -> {
            if (ATTRIBUTE_KEYS.contains(k) && v != null && v.length() <= MAX_ATTRIBUTE_VALUE) {
                kept.put(k, v);
            }
        });
        return kept;
    }

    private String audit(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise an audit payload", e);
        }
    }
}
