package com.paypalseed.agentgateway.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Scoped, short-lived bearer tokens for agent runs. A token is an opaque 256-bit random value; only its
 * SHA-256 is stored, so the database never holds a usable token.
 */
public interface AgentTokens {

    String LOAN_READ = "loan:read";
    String SCHEDULE_READ = "schedule:read";
    String SIGNALS_READ = "signals:read";
    String INCOME_READ = "income:read";
    String PREVIEW_RUN = "preview:run";
    String PROPOSAL_SUBMIT = "proposal:submit";
    String EXTRACT_RUN = "extract:run";
    String KILLSWITCH_READ = "killswitch:read";

    Set<String> ALL_SCOPES = Set.of(LOAN_READ, SCHEDULE_READ, SIGNALS_READ, INCOME_READ, PREVIEW_RUN, PROPOSAL_SUBMIT,
            EXTRACT_RUN, KILLSWITCH_READ);

    Duration MAX_TTL = Duration.ofMinutes(10);

    record IssuedToken(String token, Instant expiresAt) {

        @Override
        public String toString() {
            return "IssuedToken[expiresAt=" + expiresAt + "]";
        }
    }

    /** Throws {@link IllegalArgumentException} for an unknown scope or a lifetime above 10 minutes. */
    IssuedToken issue(UUID runId, UUID loanId, UUID caseId, Set<String> scopes, Duration ttl);

    /** Empty for an unknown, expired or revoked token. */
    Optional<AgentPrincipal> verify(String token);

    void revoke(UUID runId);
}
