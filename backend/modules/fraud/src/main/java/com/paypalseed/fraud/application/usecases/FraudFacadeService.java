package com.paypalseed.fraud.application.usecases;

import com.paypalseed.fraud.api.FraudFacade;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagDetail;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagEvidence;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagPage;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagStatus;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagView;
import com.paypalseed.fraud.api.dto.FraudDtos.Observation;
import com.paypalseed.fraud.api.dto.FraudDtos.ReviewDecision;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class FraudFacadeService implements FraudFacade {

    private static final int OPEN_FLAGS_SHOWN = 200;

    private final FraudSignals signals;
    private final FlagQueries queries;
    private final FlagReview review;

    FraudFacadeService(FraudSignals signals, FlagQueries queries, FlagReview review) {
        this.signals = signals;
        this.queries = queries;
        this.review = review;
    }

    @Override
    public void observe(Observation observation) {
        signals.observe(observation);
    }

    @Override
    public void payoutDestination(UUID memberId, String reference, Instant occurredAt, String sourceKey) {
        signals.payoutDestination(memberId, reference, occurredAt, sourceKey);
    }

    @Override
    public List<FlagView> openFlags() {
        return queries.page(List.of(FlagStatus.OPEN), null, OPEN_FLAGS_SHOWN).rows();
    }

    @Override
    public FlagPage flags(List<FlagStatus> statuses, UUID before, int limit) {
        return queries.page(statuses, before, limit);
    }

    @Override
    public Optional<FlagView> flagOf(UUID memberId) {
        return queries.flagOf(memberId);
    }

    @Override
    public Optional<FlagDetail> flag(UUID flagId) {
        return queries.detail(flagId);
    }

    @Override
    public Optional<FlagEvidence> evidence(UUID flagId) {
        return queries.evidence(flagId);
    }

    @Override
    public FlagView review(UUID flagId, ReviewDecision decision, String note, UUID staffId, String idempotencyKey) {
        return review.review(flagId, decision, note, staffId, idempotencyKey);
    }
}
