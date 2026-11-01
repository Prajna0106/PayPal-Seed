package com.paypalseed.fraud.infrastructure.persistence;

import static com.paypalseed.fraud.infrastructure.persistence.jooq.Tables.FACT;
import static com.paypalseed.fraud.infrastructure.persistence.jooq.Tables.FLAG;
import static com.paypalseed.fraud.infrastructure.persistence.jooq.Tables.HASH_SALT;
import static com.paypalseed.fraud.infrastructure.persistence.jooq.Tables.PAYOUT_DESTINATION;
import static com.paypalseed.fraud.infrastructure.persistence.jooq.Tables.SIGNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagStatus;
import com.paypalseed.fraud.api.dto.FraudDtos.ReviewDecision;
import com.paypalseed.fraud.domain.model.Fact;
import com.paypalseed.fraud.domain.model.Signal;
import com.paypalseed.fraud.infrastructure.persistence.DestinationRepository.Change;
import com.paypalseed.fraud.infrastructure.persistence.FlagRepository.FlagRow;
import com.paypalseed.fraud.infrastructure.persistence.SignalRepository.StoredSignal;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.persistence.ModuleMigrations;
import com.paypalseed.platform.testing.EmbeddedPostgresSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The fraud schema against a real PostgreSQL 16: constraints, append-only triggers and the queries the rules depend on. */
class FraudRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-11-10T12:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private static DSLContext dsl;
    private static FactRepository facts;
    private static FlagRepository flags;
    private static SignalRepository signals;
    private static ReviewRepository reviews;
    private static DestinationRepository destinations;

    @BeforeAll
    static void migrate() {
        DataSource ds = new DriverManagerDataSource(EmbeddedPostgresSupport.database("fraud_repository"));
        new ModuleMigrations(ds).migrate();
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        FraudJson json = new FraudJson(new ObjectMapper());
        facts = new FactRepository(dsl, json);
        flags = new FlagRepository(dsl);
        signals = new SignalRepository(dsl, json);
        reviews = new ReviewRepository(dsl);
        destinations = new DestinationRepository(dsl);
    }

    @Test
    void thePayoutDestinationIsABaselineFirstThenSameOrChanged() {
        UUID member = Ids.newId();
        assertThat(destinations.record(member, HASH_A, T0)).as("the first one seen is the baseline").isEqualTo(Change.FIRST);
        assertThat(destinations.record(member, HASH_A, T0.plusSeconds(60))).isEqualTo(Change.SAME);
        assertThat(destinations.record(member, HASH_B, T0.plusSeconds(120))).isEqualTo(Change.CHANGED);
        assertThat(destinations.record(member, HASH_B, T0.plusSeconds(180))).isEqualTo(Change.SAME);
        assertThat(destinations.record(member, HASH_A, T0.plusSeconds(240))).as("changing back is a change too").isEqualTo(Change.CHANGED);
        assertThat(dsl.fetchCount(PAYOUT_DESTINATION, PAYOUT_DESTINATION.MEMBER_ID.eq(member))).isEqualTo(1);
        assertThat(destinations.record(Ids.newId(), HASH_A, T0)).as("another member has a destination of their own").isEqualTo(Change.FIRST);
    }

    @Test
    void aDestinationMustBeAHashNeverAnIdentifier() {
        assertThatThrownBy(() -> destinations.record(Ids.newId(), "PAYER-123", T0)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void theSaltIsCreatedByTheMigrationAndNeverChanges() {
        String salt = facts.salt();
        assertThat(salt).hasSizeGreaterThanOrEqualTo(32);
        assertThat(facts.salt()).isEqualTo(salt);
        assertThatThrownBy(() -> dsl.update(HASH_SALT).set(HASH_SALT.SALT, "x".repeat(40)).execute()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> dsl.deleteFrom(HASH_SALT).execute()).hasMessageContaining("append-only");
    }

    @Test
    void aFactIsRecordedOncePerSourceKeyAndNeverEdited() {
        UUID member = Ids.newId();
        String key = "goal:" + Ids.newId();
        Long id = facts.insert(member, FactKind.GOAL_CREATED, T0, key, null, null, Map.of("goalId", "g1")).orElseThrow();
        assertThat(facts.insert(member, FactKind.GOAL_CREATED, T0, key, null, null, Map.of())).as("a replayed event").isEmpty();

        Fact stored = facts.byIds(List.of(id)).get(id);
        assertThat(stored.kind()).isEqualTo(FactKind.GOAL_CREATED);
        assertThat(stored.attributes()).containsEntry("goalId", "g1");
        assertThat(stored.occurredAt()).isEqualTo(T0);

        assertThatThrownBy(() -> dsl.update(FACT).set(FACT.AMOUNT_CENTS, 5L).where(FACT.ID.eq(id)).execute()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> dsl.deleteFrom(FACT).where(FACT.ID.eq(id)).execute()).hasMessageContaining("append-only");
    }

    @Test
    void aReferenceMustBeAHashNotAnIdentifier() {
        assertThatThrownBy(() -> facts.insert(Ids.newId(), FactKind.PAYER_SEEN, T0, "payer:" + Ids.newId(), null, "PAYERID1234567",
                Map.of())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> facts.insert(Ids.newId(), FactKind.DEPOSIT, T0, "dep:" + Ids.newId(), -1L, null, Map.of()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void factsOfAMemberComeBackInTimeOrderWithinTheLookback() {
        UUID member = Ids.newId();
        facts.insert(member, FactKind.DEPOSIT, T0.plusSeconds(60), "k1-" + member, 100L, null, Map.of());
        facts.insert(member, FactKind.DEPOSIT, T0, "k2-" + member, 200L, null, Map.of());
        facts.insert(member, FactKind.DEPOSIT, T0.minusSeconds(86_400L * 100), "k3-" + member, 300L, null, Map.of());
        facts.insert(Ids.newId(), FactKind.DEPOSIT, T0, "k4-" + member, 400L, null, Map.of());

        List<Fact> recent = facts.factsOf(member, T0.minusSeconds(86_400L * 90));
        assertThat(recent).extracting(Fact::amountCents).containsExactly(200L, 100L);
    }

    @Test
    void sharedPayersAreFoundByHashAcrossMembers() {
        UUID one = Ids.newId();
        UUID two = Ids.newId();
        UUID three = Ids.newId();
        String ref = "c".repeat(64);
        for (UUID m : List.of(one, two, three)) {
            facts.insert(m, FactKind.PAYER_SEEN, T0, "payer:" + m, null, ref, Map.of());
            facts.insert(m, FactKind.PAYER_SEEN, T0.plusSeconds(1), "payer2:" + m, null, ref, Map.of()); // same member twice
        }
        facts.insert(one, FactKind.DEPOSIT, T0, "dep:" + one, 100L, ref, Map.of()); // only payer facts count

        assertThat(facts.otherMembersByRef(Set.of(ref, HASH_B), one)).containsEntry(ref, 2).doesNotContainKey(HASH_B);
        assertThat(facts.membersSharing(Set.of(ref), one, 10)).containsExactlyInAnyOrder(two, three);
        assertThat(facts.membersSharing(Set.of(ref), one, 1)).hasSize(1);
        assertThat(facts.membersSharing(Set.of(), one, 10)).isEmpty();
        assertThat(facts.otherMembersByRef(Set.of(), one)).isEmpty();
    }

    @Test
    void aFindingIsStoredOnceAndAttachedToAFlagOnce() {
        UUID member = Ids.newId();
        Signal signal = new Signal("GOAL_VELOCITY", 20, "4 new savings goals within 24 hours", Map.of("count", 4), List.of(1L, 2L, 3L, 4L), T0);
        assertThat(signals.insertIfAbsent(member, signal)).isTrue();
        assertThat(signals.insertIfAbsent(member, signal)).as("same rule, same facts, same numbers").isFalse();

        List<StoredSignal> pending = signals.unattached(member);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).signal().factIds()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(pending.get(0).signal().evidence()).containsEntry("count", 4).doesNotContainKey("factIds");

        UUID flagId = Ids.newId();
        flags.insert(flagId, member, 20, T0);
        signals.attach(flagId, List.of(pending.get(0).id()));
        assertThat(signals.unattached(member)).isEmpty();
        assertThat(signals.ofFlag(flagId)).extracting(s -> s.signal().code()).containsExactly("GOAL_VELOCITY");
        assertThat(signals.codesByFlag(List.of(flagId))).containsEntry(flagId, List.of("GOAL_VELOCITY"));

        long signalId = pending.get(0).id();
        UUID otherFlag = Ids.newId();
        flags.insert(otherFlag, Ids.newId(), 0, T0);
        assertThatThrownBy(() -> dsl.update(SIGNAL).set(SIGNAL.FLAG_ID, otherFlag).where(SIGNAL.ID.eq(signalId)).execute())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> dsl.update(SIGNAL).set(SIGNAL.WEIGHT, 1).where(SIGNAL.ID.eq(signalId)).execute())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> dsl.deleteFrom(SIGNAL).where(SIGNAL.ID.eq(signalId)).execute()).hasMessageContaining("append-only");
        assertThat(signals.codesByFlag(List.of())).isEmpty();
    }

    @Test
    void aSignalCannotBeEditedBeforeItIsAttachedEither() {
        UUID member = Ids.newId();
        signals.insertIfAbsent(member, new Signal("DEPOSIT_CHURN", 15, "x", Map.of("roundTrips", 1), List.of(9L), T0));
        assertThatThrownBy(() -> dsl.update(SIGNAL).set(SIGNAL.WEIGHT, 99).where(SIGNAL.MEMBER_ID.eq(member)).execute())
                .hasMessageContaining("append-only");
    }

    @Test
    void oneFlagMayBeOpenPerMemberAndAReviewedOneIsFinal() {
        UUID member = Ids.newId();
        UUID first = Ids.newId();
        flags.insert(first, member, 40, T0);
        assertThatThrownBy(() -> flags.insert(Ids.newId(), member, 50, T0)).isInstanceOf(DataAccessException.class);
        assertThat(flags.openFor(member)).map(FlagRow::id).contains(first);

        flags.setScore(first, 55);
        assertThat(flags.find(first).orElseThrow().score()).isEqualTo(55);

        flags.markReviewed(first, FlagStatus.REVIEWED_CLEAR, T0.plusSeconds(10));
        FlagRow reviewed = flags.find(first).orElseThrow();
        assertThat(reviewed.status()).isEqualTo(FlagStatus.REVIEWED_CLEAR);
        assertThat(reviewed.reviewedAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(flags.openFor(member)).isEmpty();

        flags.markReviewed(first, FlagStatus.CONFIRMED, T0.plusSeconds(20));
        assertThat(flags.find(first).orElseThrow().status()).as("a reviewed flag cannot be re-reviewed").isEqualTo(FlagStatus.REVIEWED_CLEAR);

        UUID second = Ids.newId();
        flags.insert(second, member, 45, T0.plusSeconds(100));
        assertThat(flags.latestFor(member)).map(FlagRow::id).contains(second);
        assertThat(flags.latestFor(Ids.newId())).isEmpty();
        assertThatThrownBy(() -> dsl.deleteFrom(FLAG).where(FLAG.ID.eq(first)).execute()).hasMessageContaining("append-only");
    }

    @Test
    void aFlagIsOpenOnlyUntilItHasAReviewTime() {
        UUID flag = Ids.newId();
        flags.insert(flag, Ids.newId(), 40, T0);
        assertThatThrownBy(() -> dsl.update(FLAG).set(FLAG.STATUS, "CONFIRMED").where(FLAG.ID.eq(flag)).execute())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void oneReviewPerFlagAndAConfirmationNeedsANote() {
        UUID flagId = Ids.newId();
        flags.insert(flagId, Ids.newId(), 40, T0);
        UUID staff = Ids.newId();
        assertThatThrownBy(() -> reviews.insert(Ids.newId(), flagId, ReviewDecision.CONFIRM, null, staff, "k-" + Ids.newId(), "h"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> reviews.insert(Ids.newId(), flagId, ReviewDecision.CONFIRM, "   ", staff, "k-" + Ids.newId(), "h"))
                .isInstanceOf(DataAccessException.class);

        String key = "k-" + Ids.newId();
        reviews.insert(Ids.newId(), flagId, ReviewDecision.CLEAR, null, staff, key, "h");
        assertThat(reviews.ofFlag(flagId)).hasValueSatisfying(r -> {
            assertThat(r.decision()).isEqualTo(ReviewDecision.CLEAR);
            assertThat(r.staffId()).isEqualTo(staff);
        });
        assertThat(reviews.byKey(key)).isPresent();
        assertThat(reviews.byKey("unknown")).isEmpty();
        assertThatThrownBy(() -> reviews.insert(Ids.newId(), flagId, ReviewDecision.CLEAR, null, staff, "k-" + Ids.newId(), "h"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> dsl.deleteFrom(com.paypalseed.fraud.infrastructure.persistence.jooq.Tables.REVIEW).execute())
                .hasMessageContaining("append-only");
    }

    @Test
    void pagesAreNewestFirstWithAKeysetCursorAndAStatusFilter() throws InterruptedException {
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID id = Ids.newId();
            flags.insert(id, Ids.newId(), 40 + i, T0);
            ids.add(id);
            Thread.sleep(3);
        }
        flags.markReviewed(ids.get(1), FlagStatus.CONFIRMED, T0);

        List<UUID> newestFirst = flags.page(List.of(), null, 500).stream().map(FlagRow::id).filter(ids::contains).toList();
        assertThat(newestFirst).containsExactly(ids.get(3), ids.get(2), ids.get(1), ids.get(0));
        assertThat(flags.page(List.of(), ids.get(2), 2)).extracting(FlagRow::id).containsExactly(ids.get(1), ids.get(0));
        assertThat(flags.page(List.of(FlagStatus.CONFIRMED), null, 500)).extracting(FlagRow::id).contains(ids.get(1))
                .doesNotContain(ids.get(0));
        assertThat(flags.page(List.of(FlagStatus.OPEN), null, 500)).extracting(FlagRow::id).doesNotContain(ids.get(1)).contains(ids.get(0));
    }
}
