package com.paypalseed.creditrecord.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.HistoryPage;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.IngestOutcome;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.LevelView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.RecordView;
import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.creditrecord.api.events.RecordLevelChanged;
import com.paypalseed.creditrecord.support.InMemoryStores;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordIngestTest {

    private static final UUID ANA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID LOAN = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final InMemoryStores.Records records = new InMemoryStores.Records();
    private final List<Object> published = new ArrayList<>();
    private final RecordIngest ingest = new RecordIngest(records, published::add);
    private final RecordQueries queries = new RecordQueries(records);

    private IngestOutcome deposit(String key, int month) {
        return ingest.ingest(RecordFact.savingsDeposit(key, ANA, LocalDate.of(2026, month, 5)));
    }

    @Test
    void aRedeliveredFactChangesNothingAndPublishesNothing() {
        deposit("d1", 1);
        deposit("d2", 2);
        assertThat(deposit("d3", 3)).isEqualTo(IngestOutcome.RECORDED);
        assertThat(published).hasSize(1);

        assertThat(deposit("d3", 3)).isEqualTo(IngestOutcome.DUPLICATE);
        assertThat(deposit("d1", 1)).isEqualTo(IngestOutcome.DUPLICATE);

        assertThat(published).hasSize(1);
        assertThat(queries.record(ANA).savings().onTimeMonths()).isEqualTo(3);
        assertThat(records.facts).hasSize(3);
    }

    @Test
    void aLevelChangeIsPublishedOnceWithTheNewLimit() {
        deposit("d1", 1);
        deposit("d2", 2);
        assertThat(published).isEmpty();
        deposit("d3", 3);

        assertThat(published).singleElement().isInstanceOfSatisfying(RecordLevelChanged.class, e -> {
            assertThat(e.memberId()).isEqualTo(ANA);
            assertThat(e.fromLevel()).isEqualTo("NEW");
            assertThat(e.toLevel()).isEqualTo("BUILDING");
            assertThat(e.toLevelRank()).isEqualTo(1);
            assertThat(e.loanLimitCents()).isEqualTo(150_000);
            assertThat(e.cause()).isEqualTo("PROMOTION");
            assertThat(e.eventId()).isNotNull();
        });
    }

    @Test
    void aDefaultPublishesAChangeWithCauseDefault() {
        deposit("d1", 1);
        deposit("d2", 2);
        deposit("d3", 3);
        published.clear();

        ingest.ingest(RecordFact.loanDefaulted(ANA, LOAN, LocalDate.of(2026, 9, 1)));

        assertThat(published).singleElement().isInstanceOfSatisfying(RecordLevelChanged.class, e -> {
            assertThat(e.fromLevel()).isEqualTo("BUILDING");
            assertThat(e.toLevel()).isEqualTo("NEW");
            assertThat(e.cause()).isEqualTo("DEFAULT");
            assertThat(e.loanLimitCents()).isZero();
        });
        assertThat(queries.levelOf(ANA).level()).isEqualTo("NEW");
    }

    @Test
    void aMemberWithNoFactsIsNewWithAZeroLimitAndAClearNextStep() {
        UUID nobody = UUID.randomUUID();
        RecordView view = queries.record(nobody);
        LevelView level = queries.levelOf(nobody);

        assertThat(view.level()).isEqualTo("NEW");
        assertThat(view.since()).isNull();
        assertThat(view.onTimeBand()).isNull();
        assertThat(view.next().level()).isEqualTo("BUILDING");
        assertThat(view.next().onTimeSavingMonthsToGo()).isEqualTo(3);
        assertThat(level.loanLimitCents()).isZero();
        assertThat(level.ladderTier()).isZero();
    }

    @Test
    void theRecordViewShowsCountsAndNeverAScore() {
        deposit("d1", 1);
        deposit("d2", 2);
        deposit("d3", 3);
        ingest.ingest(RecordFact.hardshipPlanApplied("h1", ANA, UUID.randomUUID(), LOAN, LocalDate.of(2026, 4, 1)));

        RecordView view = queries.record(ANA);

        assertThat(view.level()).isEqualTo("BUILDING");
        assertThat(view.loanLimitCents()).isEqualTo(150_000);
        assertThat(view.ladderTier()).isEqualTo(1);
        assertThat(view.hardshipPlansOnPlan()).isEqualTo(1);
        assertThat(view.defaults()).isZero();
        assertThat(view.onTimeBand()).isEqualTo("95-100%");
        assertThat(view.next().level()).isEqualTo("STEADY");
        assertThat(view.next().loansRepaidOnPlanToGo()).isEqualTo(1);
        assertThat(RecordView.class.getRecordComponents()).extracting(c -> c.getName()).doesNotContain("score", "risk", "riskLevel");
    }

    @Test
    void historyPagesNewestFirstWithAKeysetCursor() {
        for (int i = 1; i <= 5; i++) {
            ingest.ingest(RecordFact.loanDisbursed("l" + i, ANA, UUID.randomUUID(), LocalDate.of(2026, i, 1)));
        }
        HistoryPage first = queries.history(ANA, null, 2);
        assertThat(first.items()).extracting(i -> i.seq()).containsExactly(5L, 4L);
        assertThat(first.nextBefore()).isEqualTo(4L);

        HistoryPage second = queries.history(ANA, first.nextBefore(), 2);
        assertThat(second.items()).extracting(i -> i.seq()).containsExactly(3L, 2L);

        HistoryPage last = queries.history(ANA, second.nextBefore(), 2);
        assertThat(last.items()).extracting(i -> i.seq()).containsExactly(1L);
        assertThat(last.nextBefore()).isNull();
        assertThat(queries.history(UUID.randomUUID(), null, 10).items()).isEmpty();
        assertThat(queries.history(ANA, null, 10_000).items()).hasSize(5); // clamped, not an error
    }

    @Test
    void closingMonthsFindsOnlyMembersWhoAreBehindAndIsIdempotent() {
        deposit("d1", 1);
        SavingsMonthClose close = new SavingsMonthClose(records, ingest);

        assertThat(close.closeThrough(LocalDate.of(2026, 2, 20), 100)).isZero(); // January just ended, February is running
        assertThat(close.closeThrough(LocalDate.of(2026, 4, 2), 100)).isEqualTo(1);   // February and March are over
        assertThat(queries.record(ANA).savings().missedMonths()).isEqualTo(2);

        assertThat(close.closeThrough(LocalDate.of(2026, 4, 20), 100)).isZero();
        assertThat(queries.record(ANA).savings().missedMonths()).isEqualTo(2);
    }

    @Test
    void aFactMissingWhatItsKindNeedsIsRefusedBeforeItReachesTheStore() {
        assertThatThrownBy(() -> new RecordFact("k", ANA, RecordFact.Kind.INSTALMENT_PAID, LocalDate.now(), LOAN, null, 0, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RecordFact.instalmentPaid(ANA, LOAN, 1, -1, LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecordFact.savingsDeposit(" ", ANA, LocalDate.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecordFact.instalmentMissed("k", ANA, LOAN, 0, LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
