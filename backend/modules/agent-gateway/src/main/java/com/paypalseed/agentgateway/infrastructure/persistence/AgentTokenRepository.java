package com.paypalseed.agentgateway.infrastructure.persistence;

import static com.paypalseed.agentgateway.infrastructure.persistence.jooq.Tables.AGENT_TOKEN;

import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class AgentTokenRepository {

    public record StoredToken(UUID runId, UUID loanId, UUID caseId, Set<String> scopes, Instant expiresAt,
                              Instant revokedAt) {
    }

    private final DSLContext dsl;

    public AgentTokenRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(String jti, UUID runId, UUID loanId, UUID caseId, Set<String> scopes, Instant issuedAt,
                       Instant expiresAt) {
        dsl.insertInto(AGENT_TOKEN)
                .set(AGENT_TOKEN.JTI, jti)
                .set(AGENT_TOKEN.RUN_ID, runId)
                .set(AGENT_TOKEN.LOAN_ID, loanId)
                .set(AGENT_TOKEN.CASE_ID, caseId)
                .set(AGENT_TOKEN.SCOPES, scopes.stream().sorted().toArray(String[]::new))
                .set(AGENT_TOKEN.ISSUED_AT, issuedAt)
                .set(AGENT_TOKEN.EXPIRES_AT, expiresAt)
                .execute();
    }

    public Optional<StoredToken> find(String jti) {
        return dsl.selectFrom(AGENT_TOKEN).where(AGENT_TOKEN.JTI.eq(jti))
                .fetchOptional(r -> new StoredToken(r.getRunId(), r.getLoanId(), r.getCaseId(), Set.of(r.getScopes()),
                        r.getExpiresAt(), r.getRevokedAt()));
    }

    public void revokeRun(UUID runId) {
        dsl.update(AGENT_TOKEN)
                .set(AGENT_TOKEN.REVOKED_AT, PgSql.now())
                .where(AGENT_TOKEN.RUN_ID.eq(runId).and(AGENT_TOKEN.REVOKED_AT.isNull()))
                .execute();
    }
}
