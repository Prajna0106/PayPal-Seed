package com.paypalseed.governance.application.usecases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.governance.api.ProposalValidator;
import com.paypalseed.governance.api.dto.GovernanceDtos.Check;
import com.paypalseed.governance.api.dto.GovernanceDtos.ProposalEnvelope;
import com.paypalseed.governance.api.dto.GovernanceDtos.ProposalResult;
import com.paypalseed.governance.api.dto.GovernanceDtos.SubmitProposal;
import com.paypalseed.governance.api.dto.GovernanceDtos.Verdict;
import com.paypalseed.governance.domain.model.AuditHash;
import com.paypalseed.governance.domain.model.GovernanceErrors;
import com.paypalseed.governance.infrastructure.persistence.ProposalRepository;
import com.paypalseed.governance.infrastructure.persistence.ProposalRepository.StoredProposal;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proposal intake (ADR 0003). The proposal is stored before it is judged, so a rejected one is still on
 * record, and the verdict is stored with every check so the reason for a rejection can be audited.
 */
@Service
public class ProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Check>> CHECKS = new TypeReference<>() {
    };

    private final ProposalRepository proposals;
    private final ValidatorRegistry validators;
    private final AuditTrail audit;

    public ProposalService(ProposalRepository proposals, ValidatorRegistry validators, AuditTrail audit) {
        this.proposals = proposals;
        this.validators = validators;
        this.audit = audit;
    }

    @Transactional
    public ProposalResult submit(SubmitProposal command) {
        String payload = canonicalObject(command.payloadJson());
        String payloadHash = AuditHash.sha256Hex(payload);
        var existing = proposals.byIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), command, payloadHash);
        }
        ProposalValidator validator = validators.forKind(command.kind()).orElseThrow(() -> new DomainException(
                GovernanceErrors.PROPOSAL_KIND_UNKNOWN, "No validator for kind " + command.kind()));
        UUID id = Ids.newId();
        if (!proposals.insert(id, command.kind(), command.subjectId(), payload, payloadHash, command.submittedBy(),
                command.idempotencyKey())) {
            return replay(proposals.byIdempotencyKey(command.idempotencyKey()).orElseThrow(), command, payloadHash);
        }
        audit.append("PROPOSAL_SUBMITTED", command.subjectId(), "SUBMITTER", command.submittedBy(),
                json(Map.of("proposalId", id, "kind", command.kind(), "payloadHash", payloadHash)));

        Verdict verdict = judge(validator, new ProposalEnvelope(id, command.kind(), command.subjectId(), payload,
                command.submittedBy()));
        boolean approvable = verdict.approvable() && verdict.checks().stream().allMatch(Check::passed);
        proposals.insertVerdict(id, approvable, json(verdict.checks()), validator.version());
        audit.append("PROPOSAL_VERDICT", command.subjectId(), "SYSTEM", "validator:" + validator.version(),
                json(Map.of("proposalId", id, "result", approvable ? "APPROVABLE" : "REJECTED",
                        "failedChecks", verdict.checks().stream().filter(c -> !c.passed()).map(Check::code).toList())));
        return new ProposalResult(id, approvable, verdict.checks(), false);
    }

    /** A validator that throws is a rejection, never a silent approval. */
    private static Verdict judge(ProposalValidator validator, ProposalEnvelope envelope) {
        try {
            Verdict verdict = validator.validate(envelope);
            if (verdict != null) {
                return verdict;
            }
            return new Verdict(false, List.of(new Check("VALIDATOR_ERROR", false, "validator returned no verdict")));
        } catch (RuntimeException e) {
            log.warn("Validator {} failed on proposal {}", validator.version(), envelope.proposalId(), e);
            return new Verdict(false, List.of(new Check("VALIDATOR_ERROR", false, e.getClass().getSimpleName())));
        }
    }

    private ProposalResult replay(StoredProposal stored, SubmitProposal command, String payloadHash) {
        if (!stored.payloadHash().equals(payloadHash) || !stored.kind().equals(command.kind())
                || !stored.subjectId().equals(command.subjectId())) {
            throw new DomainException(CommonErrors.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key " + command.idempotencyKey() + " was used for a different proposal");
        }
        var verdict = proposals.verdict(stored.id())
                .orElseThrow(() -> new IllegalStateException("proposal " + stored.id() + " has no verdict"));
        return new ProposalResult(stored.id(), verdict.approvable(), parseChecks(verdict.checksJson()), true);
    }

    private static String canonicalObject(String payloadJson) {
        JsonNode node;
        try {
            node = CanonicalJson.parse(payloadJson);
        } catch (IllegalArgumentException e) {
            throw new DomainException(GovernanceErrors.PROPOSAL_PAYLOAD_INVALID, "Payload is not valid JSON");
        }
        if (!node.isObject()) {
            throw new DomainException(GovernanceErrors.PROPOSAL_PAYLOAD_INVALID, "Payload must be a JSON object");
        }
        return CanonicalJson.of(node);
    }

    private static List<Check> parseChecks(String checksJson) {
        try {
            return JSON.readValue(checksJson, CHECKS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored checks are not valid JSON", e);
        }
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit payload must serialise", e);
        }
    }
}
