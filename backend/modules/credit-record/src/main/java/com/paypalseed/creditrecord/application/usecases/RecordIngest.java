package com.paypalseed.creditrecord.application.usecases;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.IngestOutcome;
import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.creditrecord.api.events.RecordLevelChanged;
import com.paypalseed.creditrecord.application.ports.RecordStore;
import com.paypalseed.creditrecord.domain.model.RecordProjector;
import com.paypalseed.creditrecord.domain.model.RecordProjector.Applied;
import com.paypalseed.creditrecord.domain.model.RecordState;
import com.paypalseed.platform.kernel.Ids;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records one fact and folds it into the member's state, in one transaction. The fact key makes a redelivery a
 * no-op; the member lock makes two facts for the same member fold one after the other, in the order stored.
 *
 * <p>A {@code LOAN_VOIDED} fact (ADR 0024, ADR 0025 follow-up) rebuilds the member's state without that loan and
 * publishes the level move, if any, as a {@code CORRECTION}.
 */
@Service
public class RecordIngest {

    private final RecordStore store;
    private final ApplicationEventPublisher events;

    public RecordIngest(RecordStore store, ApplicationEventPublisher events) {
        this.store = store;
        this.events = events;
    }

    @Transactional
    public IngestOutcome ingest(RecordFact fact) {
        store.lock(fact.memberId());
        if (!store.append(fact)) {
            return IngestOutcome.DUPLICATE;
        }
        RecordState before = store.find(fact.memberId())
                .orElseGet(() -> RecordState.initial(fact.memberId(), fact.occurredOn()));
        // A void takes a whole loan back, including a default or a promotion it caused, so the state is rebuilt from the
        // facts without it. Every other fact, and any later fact of a voided loan, folds on top of the current state.
        Applied applied = fact.kind() == RecordFact.Kind.LOAN_VOIDED
                ? RecordProjector.replay(fact.memberId(), before.level(), store.facts(fact.memberId()))
                : RecordProjector.apply(before, fact);
        store.save(applied.state());
        if (applied.levelChanged()) {
            events.publishEvent(new RecordLevelChanged(Ids.newId(), fact.memberId(), applied.from().name(),
                    applied.to().name(), applied.to().rank(), applied.to().loanLimitCents(), applied.cause().name(),
                    fact.occurredOn()));
        }
        return IngestOutcome.RECORDED;
    }
}
