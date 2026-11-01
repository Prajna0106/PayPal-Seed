package com.paypalseed.creditrecord.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.IngestOutcome;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareDetail;
import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.creditrecord.application.ports.RecordStore.StoredFact;
import com.paypalseed.creditrecord.application.ports.ShareStore.Share;
import com.paypalseed.creditrecord.application.ports.ShareStore.Snapshot;
import com.paypalseed.creditrecord.application.usecases.RecordIngest;
import com.paypalseed.creditrecord.application.usecases.RecordQueries;
import com.paypalseed.creditrecord.domain.model.RecordProjector;
import com.paypalseed.creditrecord.domain.model.RecordState;
import com.paypalseed.engines.rules.record.RecordFacts;
import com.paypalseed.engines.rules.record.RecordLevel;
import com.paypalseed.platform.persistence.ModuleMigrations;
import com.paypalseed.platform.testing.EmbeddedPostgresSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** The two repositories against a real PostgreSQL 16, with the module's own migrations. */
class RecordRepositoryTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static RecordRepository records;
    private static ShareRepository shares;

    @BeforeAll
    static void database() {
        String url = EmbeddedPostgresSupport.database("creditrecord_repository");
        DriverManagerDataSource plain = new DriverManagerDataSource(url, "postgres", "postgres");
        new ModuleMigrations(plain).migrate();
        dataSource = new TransactionAwareDataSourceProxy(plain);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(plain));
        DSLContext dsl = DSL.using(new DefaultConfiguration().set(new DataSourceConnectionProvider(dataSource))
                .set(SQLDialect.POSTGRES));
        records = new RecordRepository(dsl);
        shares = new ShareRepository(dsl);
    }

    private static UUID member() {
        return UUID.randomUUID();
    }

    @Test
    void aFactKeyIsStoredOnceAndFactsCannotBeChangedOrDeleted() {
        UUID ana = member();
        RecordFact fact = RecordFact.savingsDeposit("repo-dedupe-" + ana, ana, LocalDate.of(2026, 1, 5));

        assertThat(records.append(fact)).isTrue();
        assertThat(records.append(fact)).isFalse();

        assertThatThrownBy(() -> jdbc.update("UPDATE creditrecord.record_fact SET occurred_on = '2020-01-01' WHERE member_id = ?", ana))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM creditrecord.record_fact WHERE member_id = ?", ana))
                .hasMessageContaining("append-only");
    }

    @Test
    void theDatabaseRefusesAFactWithoutWhatItsKindNeeds() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO creditrecord.record_fact (fact_key, member_id, kind, occurred_on, loan_id, inst_seq)
                VALUES ('shape-1', ?, 'INSTALMENT_PAID', '2026-01-01', ?, 1)""", member(), UUID.randomUUID()))
                .hasMessageContaining("fact_shape");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO creditrecord.record_fact (fact_key, member_id, kind, occurred_on) VALUES ('shape-2', ?, 'NOT_A_KIND', '2026-01-01')""",
                member())).hasMessageContaining("record_fact_kind_check");
    }

    @Test
    void stateRoundTripsWithEveryFieldAndIsUpdatedInPlace() {
        UUID ana = member();
        RecordState state = new RecordState(ana, RecordLevel.STEADY, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 8, 2),
                new RecordFacts(7, 1, 1, 2, 9, 3, 2), new RecordFacts(4, 0, 1, 0, 6, 1, 2), 3, 2, 1,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 1), 2, Set.of("a:1", "b:2"), Set.of("a:3", "c:4", "d:5"), 41);

        records.save(state);
        assertThat(records.find(ana)).contains(state);

        RecordState changed = RecordProjector.apply(state, RecordFact.hardshipPlanApplied("repo-h", ana, UUID.randomUUID(),
                UUID.randomUUID(), LocalDate.of(2026, 10, 1))).state();
        records.save(changed);
        assertThat(records.find(ana)).contains(changed);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM creditrecord.member_record WHERE member_id = ?", Integer.class, ana))
                .isEqualTo(1);
        assertThat(records.find(member())).isEmpty();
    }

    @Test
    void historyIsNewestFirstAndScopedToTheMember() {
        UUID ana = member();
        UUID ben = member();
        for (int i = 1; i <= 4; i++) {
            records.append(RecordFact.loanDisbursed("repo-hist-" + ana + i, ana, UUID.randomUUID(), LocalDate.of(2026, i, 1)));
        }
        records.append(RecordFact.loanDisbursed("repo-hist-ben", ben, UUID.randomUUID(), LocalDate.of(2026, 1, 1)));

        List<StoredFact> page = records.history(ana, null, 3);
        assertThat(page).hasSize(3);
        assertThat(page).extracting(StoredFact::occurredOn).containsExactly(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 2, 1));
        assertThat(records.history(ana, page.get(2).seq(), 3)).extracting(StoredFact::occurredOn)
                .containsExactly(LocalDate.of(2026, 1, 1));
        assertThat(records.history(ben, null, 10)).hasSize(1);
    }

    @Test
    void onlyMembersBehindOnSavingMonthsAreDueForTheClose() {
        UUID behind = member();
        UUID current = member();
        UUID neverSaved = member();
        records.save(RecordState.initial(neverSaved, LocalDate.of(2026, 1, 1)));
        records.save(withAccounted(behind, LocalDate.of(2026, 3, 1)));
        records.save(withAccounted(current, LocalDate.of(2026, 5, 1)));

        List<UUID> due = records.membersToClose(LocalDate.of(2026, 5, 1), 100);

        assertThat(due).contains(behind).doesNotContain(current, neverSaved);
    }

    private static RecordState withAccounted(UUID member, LocalDate accountedThrough) {
        RecordState s = RecordState.initial(member, LocalDate.of(2026, 1, 1));
        return new RecordState(s.memberId(), s.level(), s.since(), s.levelSince(), s.lifetime(), s.current(), 0, 0, 0,
                accountedThrough, accountedThrough, 0, Set.of(), Set.of(), 1);
    }

    @Test
    void sharesAreStoredLookedUpAndRevokedOnce() {
        UUID ana = member();
        Instant issued = Instant.parse("2026-11-10T20:00:00Z");
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), ana, 2, LocalDate.of(2026, 1, 1), 1, "EXCELLENT", 0,
                LocalDate.of(2026, 11, 10));
        Share share = new Share(UUID.randomUUID(), ana, snapshot.id(), ShareDetail.FULL, "k1", "idem-1", "FULL:7", issued,
                issued.plus(7, ChronoUnit.DAYS), null);

        shares.insert(snapshot, share);

        assertThat(shares.share(share.id())).contains(share);
        assertThat(shares.byIdempotencyKey(ana, "idem-1")).contains(share);
        assertThat(shares.byIdempotencyKey(member(), "idem-1")).isEmpty();
        assertThat(shares.snapshot(snapshot.id())).contains(snapshot);
        assertThat(shares.sharesOf(ana)).containsExactly(share);
        assertThat(shares.activeCount(ana, issued.plusSeconds(60))).isEqualTo(1);
        assertThat(shares.activeCount(ana, issued.plus(8, ChronoUnit.DAYS))).isZero();

        assertThat(shares.revoke(member(), share.id(), issued.plusSeconds(120))).isFalse(); // someone else's share
        assertThat(shares.share(share.id()).orElseThrow().revoked()).isFalse();
        assertThat(shares.revoke(ana, share.id(), issued.plusSeconds(120))).isTrue();
        assertThat(shares.revoke(ana, share.id(), issued.plusSeconds(999))).isTrue();     // already revoked: still fine
        assertThat(shares.share(share.id()).orElseThrow().revokedAt()).isEqualTo(issued.plusSeconds(120)); // first one stands
        assertThat(shares.activeCount(ana, issued.plusSeconds(180))).isZero();
        assertThat(shares.revoke(ana, UUID.randomUUID(), issued)).isFalse();
    }

    @Test
    void aShareCanNeverBeEditedOrDeletedOnlyRevoked() {
        UUID ana = member();
        Instant issued = Instant.parse("2026-11-10T20:00:00Z");
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), ana, 1, LocalDate.of(2026, 1, 1), 0, "FAIR", 0, LocalDate.of(2026, 11, 10));
        Share share = new Share(UUID.randomUUID(), ana, snapshot.id(), ShareDetail.LEVEL, "k1", "idem-guard", "LEVEL:7", issued,
                issued.plus(7, ChronoUnit.DAYS), null);
        shares.insert(snapshot, share);

        assertThatThrownBy(() -> jdbc.update("UPDATE creditrecord.record_share SET expires_at = expires_at + interval '1 day' WHERE id = ?",
                share.id())).hasMessageContaining("may only be revoked");
        assertThatThrownBy(() -> jdbc.update("UPDATE creditrecord.record_share SET detail = 'FULL' WHERE id = ?", share.id()))
                .hasMessageContaining("may only be revoked");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM creditrecord.record_share WHERE id = ?", share.id()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE creditrecord.record_snapshot SET level = 4 WHERE id = ?", snapshot.id()))
                .hasMessageContaining("append-only");

        shares.revoke(ana, share.id(), issued.plusSeconds(5));
        assertThatThrownBy(() -> jdbc.update("UPDATE creditrecord.record_share SET revoked_at = NULL WHERE id = ?", share.id()))
                .hasMessageContaining("may only be revoked");
    }

    @Test
    void theSameIdempotencyKeyCannotCreateTwoSharesForOneMember() {
        UUID ana = member();
        Instant issued = Instant.parse("2026-11-10T20:00:00Z");
        for (int i = 0; i < 2; i++) {
            Snapshot snapshot = new Snapshot(UUID.randomUUID(), ana, 1, LocalDate.of(2026, 1, 1), 0, "FAIR", 0, LocalDate.of(2026, 11, 10));
            Share share = new Share(UUID.randomUUID(), ana, snapshot.id(), ShareDetail.LEVEL, "k1", "idem-same", "LEVEL:7", issued,
                    issued.plus(7, ChronoUnit.DAYS), null);
            if (i == 0) {
                shares.insert(snapshot, share);
            } else {
                assertThatThrownBy(() -> tx.executeWithoutResult(s -> shares.insert(snapshot, share)))
                        .hasMessageContaining("record_share_member_id_idempotency_key_key");
            }
        }
    }

    @Test
    void aShareCannotOutliveThirtyOneDays() {
        UUID ana = member();
        Instant issued = Instant.parse("2026-11-10T20:00:00Z");
        Snapshot snapshot = new Snapshot(UUID.randomUUID(), ana, 1, LocalDate.of(2026, 1, 1), 0, "FAIR", 0, LocalDate.of(2026, 11, 10));
        Share share = new Share(UUID.randomUUID(), ana, snapshot.id(), ShareDetail.LEVEL, "k1", "idem-long", "LEVEL:32", issued,
                issued.plus(32, ChronoUnit.DAYS), null);
        assertThatThrownBy(() -> shares.insert(snapshot, share)).hasMessageContaining("share_expiry_window");
    }

    /** The member lock makes concurrent facts for one member fold one after the other: no lost update, no double count. */
    @Test
    void concurrentFactsForOneMemberAreAllCountedExactlyOnce() throws Exception {
        UUID ana = member();
        UUID loan = UUID.randomUUID();
        RecordIngest ingest = new RecordIngest(records, event -> {
        });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<IngestOutcome>> work = new ArrayList<>();
        for (int round = 0; round < 2; round++) {          // every instalment is delivered twice, at the same time
            for (int seq = 1; seq <= 12; seq++) {
                RecordFact fact = RecordFact.instalmentPaid(ana, loan, seq, 0, LocalDate.of(2026, 1, 1).plusDays(seq));
                work.add(() -> tx.execute(s -> ingest.ingest(fact)));
            }
        }
        List<IngestOutcome> outcomes = new ArrayList<>();
        for (Future<IngestOutcome> f : pool.invokeAll(work)) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        assertThat(outcomes).filteredOn(o -> o == IngestOutcome.RECORDED).hasSize(12);
        RecordState state = records.find(ana).orElseThrow();
        assertThat(state.lifetime().instalmentsOnTime()).isEqualTo(12);
        assertThat(state.factCount()).isEqualTo(12);
        assertThat(new RecordQueries(records).history(ana, null, 100).items()).hasSize(12);
    }
}
