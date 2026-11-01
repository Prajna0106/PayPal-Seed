package com.paypalseed.creditrecord.application.usecases;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.DeskRecordView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.HistoryItem;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.HistoryPage;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.InstalmentCounts;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.LevelView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.LoanCounts;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.NextStepView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.RecordView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.SavingsCounts;
import com.paypalseed.creditrecord.application.ports.RecordStore;
import com.paypalseed.creditrecord.application.ports.RecordStore.StoredFact;
import com.paypalseed.creditrecord.domain.model.RecordState;
import com.paypalseed.engines.rules.record.OnTimeBand;
import com.paypalseed.engines.rules.record.RecordFacts;
import com.paypalseed.engines.rules.record.RecordLevel;
import com.paypalseed.engines.rules.record.RecordLevels;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads of the record. A member with no facts yet is level NEW with empty counts, not an error. */
@Service
public class RecordQueries {

    static final int MAX_PAGE = 100;
    static final int DESK_RECENT = 20;

    private final RecordStore store;

    public RecordQueries(RecordStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true)
    public RecordView record(UUID memberId) {
        return store.find(memberId).map(RecordQueries::view).orElseGet(RecordQueries::emptyView);
    }

    @Transactional(readOnly = true)
    public LevelView levelOf(UUID memberId) {
        RecordLevel level = store.find(memberId).map(RecordState::level).orElse(RecordLevel.NEW);
        return new LevelView(level.name(), level.rank(), level.loanLimitCents(), level.ladderTier().level());
    }

    @Transactional(readOnly = true)
    public HistoryPage history(UUID memberId, Long before, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        List<StoredFact> facts = store.history(memberId, before, size + 1);
        boolean more = facts.size() > size;
        List<HistoryItem> items = facts.stream().limit(size)
                .map(f -> new HistoryItem(f.seq(), f.kind().name(), f.occurredOn(), f.daysLate())).toList();
        return new HistoryPage(items, more ? items.get(items.size() - 1).seq() : null);
    }

    @Transactional(readOnly = true)
    public DeskRecordView forDesk(UUID memberId) {
        return new DeskRecordView(memberId, record(memberId), history(memberId, null, DESK_RECENT).items());
    }

    public static RecordView view(RecordState s) {
        RecordFacts life = s.lifetime();
        boolean anyEvents = (long) life.onTimeSavingMonths() + life.missedSavingMonths() + life.instalmentsOnTime()
                + life.instalmentsLate() + life.openLateInstalments() > 0;
        return new RecordView(s.level().name(), s.level().rank(), s.level().loanLimitCents(),
                s.level().ladderTier().level(), s.since(), s.levelSince(), life.onTimeBps(),
                anyEvents ? OnTimeBand.of(life.onTimeBps()).label() : null,
                new SavingsCounts(life.onTimeSavingMonths(), life.missedSavingMonths()),
                new LoanCounts(s.loansDisbursed(), life.loansRepaidOnPlan(), life.loansRepaidOffPlan()),
                new InstalmentCounts(life.instalmentsOnTime(), life.instalmentsLate(), life.openLateInstalments()),
                s.hardshipPlans(), s.defaults(), nextStep(s.level(), s.current()), RecordLevels.POLICY_VERSION);
    }

    static RecordView emptyView() {
        RecordLevel level = RecordLevel.NEW;
        return new RecordView(level.name(), level.rank(), level.loanLimitCents(), level.ladderTier().level(), null, null,
                RecordFacts.FULL_BPS, null, new SavingsCounts(0, 0), new LoanCounts(0, 0, 0),
                new InstalmentCounts(0, 0, 0), 0, 0, nextStep(level, RecordFacts.EMPTY), RecordLevels.POLICY_VERSION);
    }

    private static NextStepView nextStep(RecordLevel level, RecordFacts facts) {
        return RecordLevels.nextStep(level, facts)
                .map(n -> new NextStepView(n.target().name(), n.onTimeSavingMonthsToGo(), n.loansRepaidOnPlanToGo(),
                        n.requiredOnTimeBps(), n.onTimeShareTooLow(), n.latePaymentOpen()))
                .orElse(null);
    }
}
