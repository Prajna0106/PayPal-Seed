package com.paypalseed.agentgateway.application.usecases;

import com.paypalseed.agentgateway.api.AgentPrincipal;
import com.paypalseed.agentgateway.api.AgentTokens;
import com.paypalseed.agentgateway.infrastructure.persistence.AgentTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Issues and checks agent run tokens. The token is {@code seedagt_} plus 256 random bits (the prefix lets
 * secret scanners spot a leaked one); only its SHA-256 is stored as the {@code jti}.
 */
@Service
public class AgentTokenService implements AgentTokens {

    static final String PREFIX = "seedagt_";

    private final AgentTokenRepository repository;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Autowired
    public AgentTokenService(AgentTokenRepository repository) {
        this(repository, Clock.systemUTC());
    }

    AgentTokenService(AgentTokenRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public IssuedToken issue(UUID runId, UUID loanId, UUID caseId, Set<String> scopes, Duration ttl) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(caseId, "caseId");
        if (scopes.isEmpty() || !ALL_SCOPES.containsAll(scopes)) {
            throw new IllegalArgumentException("unknown or empty scopes: " + scopes);
        }
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("an agent token lives at most " + MAX_TTL);
        }
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = issuedAt.plus(ttl);
        repository.insert(jti(token), runId, loanId, caseId, scopes, issuedAt, expiresAt);
        return new IssuedToken(token, expiresAt);
    }

    @Override
    public Optional<AgentPrincipal> verify(String token) {
        if (token == null || !token.startsWith(PREFIX) || token.length() > 128) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return repository.find(jti(token))
                .filter(t -> t.revokedAt() == null && now.isBefore(t.expiresAt()))
                .map(t -> new AgentPrincipal(t.runId(), t.loanId(), t.caseId(), t.scopes()));
    }

    @Override
    public void revoke(UUID runId) {
        repository.revokeRun(runId);
    }

    static String jti(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
