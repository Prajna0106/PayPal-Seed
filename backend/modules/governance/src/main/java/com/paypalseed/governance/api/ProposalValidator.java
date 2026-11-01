package com.paypalseed.governance.api;

import com.paypalseed.governance.api.dto.GovernanceDtos.ProposalEnvelope;
import com.paypalseed.governance.api.dto.GovernanceDtos.Verdict;

/**
 * Implemented by the module that owns a proposal kind and registered as a Spring bean (dependency
 * inversion: governance never imports its owners). Runs inside the submitting transaction. It must be
 * deterministic, must not call a model, and must return a rejecting verdict rather than throw.
 */
public interface ProposalValidator {

    String kind();

    Verdict validate(ProposalEnvelope proposal);

    default String version() {
        return kind() + "-v1";
    }
}
