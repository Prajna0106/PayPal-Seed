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
        Applied applied = RecordProjector.apply(before, fact);
        store.save(applied.state());
        if (applied.levelChanged()) {
            events.publishEvent(new RecordLevelChanged(Ids.newId(), fact.memberId(), applied.from().name(),
                    applied.to().name(), applied.to().rank(), applied.to().loanLimitCents(), applied.cause().name(),
                    fact.occurredOn()));
        }
        return IngestOutcome.RECORDED;
    }
}
