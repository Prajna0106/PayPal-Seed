package com.paypalseed.governance.infrastructure.persistence;

import static com.paypalseed.governance.infrastructure.persistence.jooq.Tables.PROPOSAL;
import static com.paypalseed.governance.infrastructure.persistence.jooq.Tables.VERDICT;

import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

/** {@code governance.proposal} and {@code governance.verdict}: both append-only. */
@Repository
public class ProposalRepository {

    public record StoredProposal(UUID id, String kind, UUID subjectId, String payloadJson, String payloadHash,
                                 String submittedBy) {
    }

    public record StoredVerdict(boolean approvable, String checksJson) {
    }

    private final DSLContext dsl;

    public ProposalRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Returns false when the idempotency key is already taken (a concurrent or earlier submit). */
    public boolean insert(UUID id, String kind, UUID subjectId, String payloadJson, String payloadHash, String submittedBy,
                          String idempotencyKey) {
        return dsl.insertInto(PROPOSAL)
                .set(PROPOSAL.ID, id)
                .set(PROPOSAL.KIND, kind)
                .set(PROPOSAL.SUBJECT_ID, subjectId)
                .set(PROPOSAL.PAYLOAD, JSONB.valueOf(payloadJson))
                .set(PROPOSAL.PAYLOAD_HASH, payloadHash)
                .set(PROPOSAL.SUBMITTED_BY, submittedBy)
                .set(PROPOSAL.IDEMPOTENCY_KEY, idempotencyKey)
                .onConflict(PROPOSAL.IDEMPOTENCY_KEY).doNothing()
                .execute() == 1;
    }

    public Optional<StoredProposal> byIdempotencyKey(String key) {
        return dsl.selectFrom(PROPOSAL).where(PROPOSAL.IDEMPOTENCY_KEY.eq(key))
                .fetchOptional(r -> new StoredProposal(r.getId(), r.getKind(), r.getSubjectId(), r.getPayload().data(),
                        r.getPayloadHash(), r.getSubmittedBy()));
    }

    public void insertVerdict(UUID proposalId, boolean approvable, String checksJson, String validatorVersion) {
        dsl.insertInto(VERDICT)
                .set(VERDICT.PROPOSAL_ID, proposalId)
                .set(VERDICT.RESULT, approvable ? "APPROVABLE" : "REJECTED")
                .set(VERDICT.CHECKS, JSONB.valueOf(checksJson))
                .set(VERDICT.VALIDATOR_VERSION, validatorVersion)
                .execute();
    }

    public Optional<StoredVerdict> verdict(UUID proposalId) {
        return dsl.selectFrom(VERDICT).where(VERDICT.PROPOSAL_ID.eq(proposalId))
                .fetchOptional(r -> new StoredVerdict("APPROVABLE".equals(r.getResult()), r.getChecks().data()));
    }
}
