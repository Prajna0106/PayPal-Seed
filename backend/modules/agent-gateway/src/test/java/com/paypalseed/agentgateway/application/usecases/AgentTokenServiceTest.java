package com.paypalseed.agentgateway.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.agentgateway.api.AgentTokens;
import com.paypalseed.agentgateway.infrastructure.persistence.AgentTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Token lifetime, scopes and revocation, with an in-memory store and a controllable clock. */
class AgentTokenServiceTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2027-03-10T17:00:00Z"));
    private final InMemoryTokens store = new InMemoryTokens();
    private final AgentTokenService tokens = new AgentTokenService(store, new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    });

    private final UUID run = UUID.randomUUID();
    private final UUID loan = UUID.randomUUID();
    private final UUID kase = UUID.randomUUID();

    @Test
    void aTokenWorksUntilItExpires() {
        var issued = tokens.issue(run, loan, kase, Set.of(AgentTokens.LOAN_READ), Duration.ofMinutes(10));
        assertThat(issued.token()).startsWith("seedagt_").hasSizeGreaterThan(40);
        assertThat(issued.toString()).doesNotContain(issued.token());
        assertThat(tokens.verify(issued.token())).get().satisfies(p -> {
            assertThat(p.runId()).isEqualTo(run);
            assertThat(p.loanId()).isEqualTo(loan);
            assertThat(p.caseId()).isEqualTo(kase);
            assertThat(p.scopes()).containsExactly(AgentTokens.LOAN_READ);
        });

        now.set(now.get().plus(Duration.ofMinutes(10)));
        assertThat(tokens.verify(issued.token())).isEmpty();
    }

    @Test
    void onlyTheHashIsStored() {
        var issued = tokens.issue(run, loan, kase, Set.of(AgentTokens.LOAN_READ), Duration.ofMinutes(1));
        assertThat(store.rows).doesNotContainKey(issued.token()).containsKey(AgentTokenService.jti(issued.token()));
    }

    @Test
    void revokingARunKillsItsTokens() {
        var issued = tokens.issue(run, loan, kase, Set.of(AgentTokens.LOAN_READ), Duration.ofMinutes(5));
        tokens.revoke(run);
        assertThat(tokens.verify(issued.token())).isEmpty();
    }

    @Test
    void refusesLongLivedTokensAndUnknownScopes() {
        assertThatThrownBy(() -> tokens.issue(run, loan, kase, Set.of(AgentTokens.LOAN_READ), Duration.ofMinutes(11)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.issue(run, loan, kase, Set.of("loan:write"), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.issue(run, loan, kase, Set.of(), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void garbageNeverVerifies() {
        assertThat(tokens.verify(null)).isEmpty();
        assertThat(tokens.verify("")).isEmpty();
        assertThat(tokens.verify("Bearer abc")).isEmpty();
        assertThat(tokens.verify("seedagt_" + "x".repeat(200))).isEmpty();
        assertThat(tokens.verify("seedagt_unknown")).isEmpty();
    }

    /** The repository's contract, kept in memory. */
    private static final class InMemoryTokens extends AgentTokenRepository {

        final Map<String, StoredToken> rows = new ConcurrentHashMap<>();

        InMemoryTokens() {
            super(null);
        }

        @Override
        public void insert(String jti, UUID runId, UUID loanId, UUID caseId, Set<String> scopes, Instant issuedAt,
                           Instant expiresAt) {
            rows.put(jti, new StoredToken(runId, loanId, caseId, Set.copyOf(scopes), expiresAt, null));
        }

        @Override
        public Optional<StoredToken> find(String jti) {
            return Optional.ofNullable(rows.get(jti));
        }

        @Override
        public void revokeRun(UUID runId) {
            rows.replaceAll((jti, t) -> t.runId().equals(runId)
                    ? new StoredToken(t.runId(), t.loanId(), t.caseId(), t.scopes(), t.expiresAt(), Instant.EPOCH) : t);
        }
    }
}
