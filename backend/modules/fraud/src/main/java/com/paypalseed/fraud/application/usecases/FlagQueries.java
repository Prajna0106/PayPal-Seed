package com.paypalseed.fraud.application.usecases;

import com.paypalseed.fraud.api.dto.FraudDtos.FactView;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagDetail;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagEvidence;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagPage;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagStatus;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagView;
import com.paypalseed.fraud.api.dto.FraudDtos.ReviewView;
import com.paypalseed.fraud.api.dto.FraudDtos.SignalEvidence;
import com.paypalseed.fraud.api.dto.FraudDtos.SignalView;
import com.paypalseed.fraud.domain.model.Fact;
import com.paypalseed.fraud.domain.rules.RuleSet;
import com.paypalseed.fraud.infrastructure.persistence.FactRepository;
import com.paypalseed.fraud.infrastructure.persistence.FlagRepository;
import com.paypalseed.fraud.infrastructure.persistence.FlagRepository.FlagRow;
import com.paypalseed.fraud.infrastructure.persistence.ReviewRepository;
import com.paypalseed.fraud.infrastructure.persistence.SignalRepository;
import com.paypalseed.fraud.infrastructure.persistence.SignalRepository.StoredSignal;
import com.paypalseed.platform.kernel.BusinessClock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What officers read: the queue, one flag, and the evidence pack behind it. */
@Service
public class FlagQueries {

    private static final int MAX_PAGE = 200;

    private final FlagRepository flags;
    private final SignalRepository signals;
    private final ReviewRepository reviews;
    private final FactRepository facts;
    private final FraudSettings settings;
    private final BusinessClock clock;

    public FlagQueries(FlagRepository flags, SignalRepository signals, ReviewRepository reviews, FactRepository facts,
                       FraudSettings settings, BusinessClock clock) {
        this.flags = flags;
        this.signals = signals;
        this.reviews = reviews;
        this.facts = facts;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FlagPage page(List<FlagStatus> statuses, UUID before, int limit) {
        int size = Math.clamp(limit, 1, MAX_PAGE);
        List<FlagRow> rows = flags.page(statuses == null ? List.of() : statuses, before, size + 1);
        boolean more = rows.size() > size;
        List<FlagRow> shown = more ? rows.subList(0, size) : rows;
        Map<UUID, List<String>> codes = signals.codesByFlag(shown.stream().map(FlagRow::id).toList());
        return new FlagPage(shown.stream().map(f -> view(f, codes.getOrDefault(f.id(), List.of()))).toList(),
                more ? shown.get(shown.size() - 1).id() : null);
    }

    @Transactional(readOnly = true)
    public Optional<FlagView> flagOf(UUID memberId) {
        return flags.latestFor(memberId).map(this::view);
    }

    @Transactional(readOnly = true)
    public Optional<FlagDetail> detail(UUID flagId) {
        return flags.find(flagId).map(f -> new FlagDetail(view(f), signals.ofFlag(f.id()).stream().map(FlagQueries::signalView).toList(),
                reviewView(f.id()), RuleSet.VERSION, settings.flagThreshold()));
    }

    @Transactional(readOnly = true)
    public Optional<FlagEvidence> evidence(UUID flagId) {
        return flags.find(flagId).map(f -> {
            List<StoredSignal> stored = signals.ofFlag(f.id());
            Set<Long> ids = new LinkedHashSet<>();
            stored.forEach(s -> ids.addAll(s.signal().factIds()));
            Map<Long, Fact> byId = facts.byIds(ids);
            List<SignalEvidence> pack = stored.stream().map(s -> new SignalEvidence(signalView(s), s.signal().factIds().stream()
                    .map(byId::get).filter(Objects::nonNull).map(FlagQueries::factView).toList())).toList();
            return new FlagEvidence(f.id(), f.memberId(), f.status(), f.score(), RuleSet.VERSION, settings.flagThreshold(),
                    clock.now(), pack, reviewView(f.id()));
        });
    }

    FlagView view(FlagRow f) {
        return view(f, signals.codesByFlag(List.of(f.id())).getOrDefault(f.id(), List.of()));
    }

    private static FlagView view(FlagRow f, List<String> codes) {
        return new FlagView(f.id(), f.memberId(), f.status(), f.score(), codes, f.raisedAt(), f.reviewedAt());
    }

    private ReviewView reviewView(UUID flagId) {
        return reviews.ofFlag(flagId).map(r -> new ReviewView(r.decision(), r.note(), r.createdAt())).orElse(null);
    }

    private static SignalView signalView(StoredSignal s) {
        Map<String, Object> evidence = new LinkedHashMap<>(s.signal().evidence());
        evidence.put("factIds", s.signal().factIds());
        return new SignalView(s.signal().code(), s.signal().weight(), s.signal().explanation(), s.signal().detectedAt(), evidence);
    }

    private static FactView factView(Fact f) {
        return new FactView(f.id(), f.kind(), f.occurredAt(), f.amountCents(),
                f.refHash() == null ? null : f.refHash().substring(0, 8), f.attributes());
    }
}
