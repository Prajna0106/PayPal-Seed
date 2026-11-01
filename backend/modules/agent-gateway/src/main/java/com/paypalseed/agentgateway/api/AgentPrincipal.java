package com.paypalseed.agentgateway.api;

import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The verified holder of an agent run token: one run, one loan, one case, a fixed set of scopes.
 * Asking about any other loan or case answers 404, exactly like a missing one, so an agent cannot probe
 * which loans exist.
 */
public record AgentPrincipal(UUID runId, UUID loanId, UUID caseId, Set<String> scopes) {

    public static final ErrorCode INSUFFICIENT_SCOPE =
            new ErrorCode("insufficient-scope", ErrorCode.Kind.FORBIDDEN, "Insufficient scope");

    public AgentPrincipal {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(caseId, "caseId");
        scopes = Set.copyOf(scopes);
    }

    public AgentPrincipal requireScope(String scope) {
        if (!scopes.contains(scope)) {
            throw new DomainException(INSUFFICIENT_SCOPE, "This token cannot use " + scope);
        }
        return this;
    }

    public AgentPrincipal requireLoan(UUID requested) {
        if (!loanId.equals(requested)) {
            throw new DomainException(CommonErrors.NOT_FOUND, "No such loan");
        }
        return this;
    }

    public AgentPrincipal requireCase(UUID requested) {
        if (!caseId.equals(requested)) {
            throw new DomainException(CommonErrors.NOT_FOUND, "No such case");
        }
        return this;
    }
}
