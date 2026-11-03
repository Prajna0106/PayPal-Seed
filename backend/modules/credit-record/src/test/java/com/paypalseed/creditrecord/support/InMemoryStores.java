package com.paypalseed.creditrecord.support;

import com.paypalseed.creditrecord.api.dto.RecordFact;
import com.paypalseed.creditrecord.application.ports.RecordStore;
import com.paypalseed.creditrecord.application.ports.ShareStore;
import com.paypalseed.creditrecord.domain.model.RecordState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Plain-memory stand-ins for the two stores, so use cases are tested without a database. */
public final class InMemoryStores {

    private InMemoryStores() {
    }

    public static final class Records implements RecordStore {

        public final Map<String, RecordFact> facts = new LinkedHashMap<>();
        public final Map<String, Long> seqs = new HashMap<>();
        public final Map<UUID, RecordState> states = new HashMap<>();
        private long next = 1;

        @Override
        public void lock(UUID memberId) {
        }

        @Override
        public boolean append(RecordFact fact) {
            if (facts.containsKey(fact.factKey())) {
                return false;
            }
            facts.put(fact.factKey(), fact);
            seqs.put(fact.factKey(), next++);
            return true;
        }

        @Override
        public Optional<RecordState> find(UUID memberId) {
            return Optional.ofNullable(states.get(memberId));
        }

        @Override
        public void save(RecordState state) {
            states.put(state.memberId(), state);
        }

        @Override
        public List<RecordFact> facts(UUID memberId) {
            return facts.values().stream().filter(f -> f.memberId().equals(memberId))
                    .sorted(Comparator.comparingLong(f -> seqs.get(f.factKey()))).toList();
        }

        @Override
        public List<StoredFact> history(UUID memberId, Long before, int limit) {
            List<StoredFact> out = new ArrayList<>();
            java.util.Set<UUID> voided = facts.values().stream().filter(f -> f.kind() == RecordFact.Kind.LOAN_VOIDED)
                    .map(RecordFact::loanId).collect(java.util.stream.Collectors.toSet());
            facts.values().stream().filter(f -> f.memberId().equals(memberId))
                    .filter(f -> f.kind() != RecordFact.Kind.LOAN_VOIDED && (f.loanId() == null || !voided.contains(f.loanId())))
                    .map(f -> new StoredFact(seqs.get(f.factKey()), f.kind(), f.occurredOn(), f.daysLate()))
                    .filter(f -> before == null || f.seq() < before)
                    .sorted(Comparator.comparingLong(StoredFact::seq).reversed()).limit(limit).forEach(out::add);
            return out;
        }

        @Override
        public List<UUID> membersToClose(LocalDate throughMonth, int limit) {
            return states.values().stream()
                    .filter(s -> s.savingAccountedThrough() != null && s.savingAccountedThrough().isBefore(throughMonth))
                    .map(RecordState::memberId).limit(limit).toList();
        }
    }

    public static final class Shares implements ShareStore {

        public final Map<UUID, Share> shares = new LinkedHashMap<>();
        public final Map<UUID, Snapshot> snapshots = new HashMap<>();

        @Override
        public void lock(UUID memberId) {
        }

        @Override
        public void insert(Snapshot snapshot, Share share) {
            snapshots.put(snapshot.id(), snapshot);
            shares.put(share.id(), share);
        }

        @Override
        public Optional<Share> share(UUID shareId) {
            return Optional.ofNullable(shares.get(shareId));
        }

        @Override
        public Optional<Share> byIdempotencyKey(UUID memberId, String idempotencyKey) {
            return shares.values().stream()
                    .filter(s -> s.memberId().equals(memberId) && s.idempotencyKey().equals(idempotencyKey)).findFirst();
        }

        @Override
        public Optional<Snapshot> snapshot(UUID snapshotId) {
            return Optional.ofNullable(snapshots.get(snapshotId));
        }

        @Override
        public List<Share> sharesOf(UUID memberId) {
            return shares.values().stream().filter(s -> s.memberId().equals(memberId))
                    .sorted(Comparator.comparing(Share::issuedAt).reversed()).toList();
        }

        @Override
        public int activeCount(UUID memberId, Instant now) {
            return (int) shares.values().stream()
                    .filter(s -> s.memberId().equals(memberId) && !s.revoked() && now.isBefore(s.expiresAt())).count();
        }

        @Override
        public boolean revoke(UUID memberId, UUID shareId, Instant at) {
            Share s = shares.get(shareId);
            if (s == null || !s.memberId().equals(memberId)) {
                return false;
            }
            if (!s.revoked()) {
                shares.put(shareId, new Share(s.id(), s.memberId(), s.snapshotId(), s.detail(), s.keyId(), s.idempotencyKey(),
                        s.fingerprint(), s.issuedAt(), s.expiresAt(), at));
            }
            return true;
        }

        @Override
        public int revokeAllActive(UUID memberId, Instant at) {
            int closed = 0;
            for (Share s : List.copyOf(shares.values())) {
                if (s.memberId().equals(memberId) && !s.revoked() && at.isBefore(s.expiresAt())) {
                    shares.put(s.id(), new Share(s.id(), s.memberId(), s.snapshotId(), s.detail(), s.keyId(), s.idempotencyKey(),
                            s.fingerprint(), s.issuedAt(), s.expiresAt(), at));
                    closed++;
                }
            }
            return closed;
        }
    }
}
