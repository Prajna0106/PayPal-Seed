package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.governance.api.ApprovalStaleException;
import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.governance.api.ProposalValidator;
import com.paypalseed.governance.api.dto.GovernanceDtos.ApprovalView;
import com.paypalseed.governance.api.dto.GovernanceDtos.AuditCommand;
import com.paypalseed.governance.api.dto.GovernanceDtos.Check;
import com.paypalseed.governance.api.dto.GovernanceDtos.Decide;
import com.paypalseed.governance.api.dto.GovernanceDtos.Decision;
import com.paypalseed.governance.api.dto.GovernanceDtos.DecisionResult;
import com.paypalseed.governance.api.dto.GovernanceDtos.OpenApproval;
import com.paypalseed.governance.api.dto.GovernanceDtos.ProposalEnvelope;
import com.paypalseed.governance.api.dto.GovernanceDtos.ProposalResult;
import com.paypalseed.governance.api.dto.GovernanceDtos.SubmitProposal;
import com.paypalseed.governance.api.dto.GovernanceDtos.Tier;
import com.paypalseed.governance.api.dto.GovernanceDtos.Verdict;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.support.IntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Governance slice: proposals and verdicts, maker-checker with separation of duties, and the audit chain. */
@Import({IntegrationTest.Fakes.class, GovernanceIT.TestValidators.class})
class GovernanceIT extends IntegrationTest {

    static final String KIND = "TEST_KIND";
    static final String SNAPSHOT = "a".repeat(64);

    /** Approves payloads with {@code "ok": true}; throws on {@code "boom"} to prove a crash is a rejection. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestValidators {
        @Bean
        ProposalValidator testKindValidator() {
            return new ProposalValidator() {
                @Override
                public String kind() {
                    return KIND;
                }

                @Override
                public Verdict validate(ProposalEnvelope proposal) {
                    if (proposal.payloadJson().contains("\"boom\"")) {
                        throw new IllegalStateException("validator crashed");
                    }
                    boolean ok = proposal.payloadJson().contains("\"ok\":true");
                    return Verdict.of(List.of(new Check("V01_SCHEMA", true, null), new Check("V99_OK", ok, "ok flag")));
                }
            };
        }
    }

    @Autowired
    GovernanceFacade governance;

    private final UUID maker = UUID.randomUUID();
    private final UUID checker = UUID.randomUUID();

    @Test
    void proposalIsStoredJudgedAndReplayedByIdempotencyKey() {
        UUID subject = Ids.newId();
        ProposalResult first = governance.submitProposal(
                new SubmitProposal(KIND, subject, "{\"ok\": true, \"n\": 1}", "agent-run:r1", "key-" + subject));
        assertThat(first.approvable()).isTrue();
        assertThat(first.replay()).isFalse();
        assertThat(first.checks()).extracting(Check::code).containsExactly("V01_SCHEMA", "V99_OK");

        ProposalResult replay = governance.submitProposal(
                new SubmitProposal(KIND, subject, "{ \"n\" : 1 , \"ok\" : true }", "agent-run:r1", "key-" + subject));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.proposalId()).isEqualTo(first.proposalId());
        assertThat(replay.checks()).isEqualTo(first.checks());

        assertThatThrownBy(() -> governance.submitProposal(
                new SubmitProposal(KIND, subject, "{\"ok\": false}", "agent-run:r1", "key-" + subject)))
                .isInstanceOf(DomainException.class).hasFieldOrPropertyWithValue("errorCode.code", "idempotency-key-reused");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM governance.proposal WHERE subject_id = :s").param("s", subject)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void failedCheckOrCrashingValidatorRejects() {
        ProposalResult rejected = governance.submitProposal(
                new SubmitProposal(KIND, Ids.newId(), "{\"ok\": false}", "agent-run:r2", "key-" + Ids.newId()));
        assertThat(rejected.approvable()).isFalse();

        ProposalResult crashed = governance.submitProposal(
                new SubmitProposal(KIND, Ids.newId(), "{\"boom\": 1}", "agent-run:r3", "key-" + Ids.newId()));
        assertThat(crashed.approvable()).isFalse();
        assertThat(crashed.checks()).extracting(Check::code).containsExactly("VALIDATOR_ERROR");
        String stored = jdbc.sql("SELECT result FROM governance.verdict WHERE proposal_id = :p")
                .param("p", crashed.proposalId()).query(String.class).single();
        assertThat(stored).isEqualTo("REJECTED");
    }

    @Test
    void unknownKindAndNonObjectPayloadAreRefused() {
        assertThatThrownBy(() -> governance.submitProposal(
                new SubmitProposal("NO_SUCH_KIND", Ids.newId(), "{}", "x", "key-" + Ids.newId())))
                .hasFieldOrPropertyWithValue("errorCode.code", "proposal-kind-unknown");
        assertThatThrownBy(() -> governance.submitProposal(
                new SubmitProposal(KIND, Ids.newId(), "[1,2]", "x", "key-" + Ids.newId())))
                .hasFieldOrPropertyWithValue("errorCode.code", "proposal-payload-invalid");
    }

    @Test
    void makerThenDifferentCheckerApproves() {
        UUID subject = Ids.newId();
        UUID request = openMakerChecker(subject, UUID.randomUUID());

        DecisionResult makerResult = governance.decide(decide(request, maker, Set.of("OFFICER"), Decision.APPROVE, SNAPSHOT));
        assertThat(makerResult.role()).isEqualTo("MAKER");
        assertThat(makerResult.requestStatus()).isEqualTo("OPEN");
        assertThat(makerResult.next()).isEqualTo("CHECKER");

        DecisionResult checkerResult = governance.decide(decide(request, checker, Set.of("CHECKER"), Decision.APPROVE, SNAPSHOT));
        assertThat(checkerResult.role()).isEqualTo("CHECKER");
        assertThat(checkerResult.requestStatus()).isEqualTo("APPROVED");
        assertThat(checkerResult.auditSeq()).isGreaterThan(makerResult.auditSeq());

        ApprovalView view = governance.request(request).orElseThrow();
        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.decisions()).extracting(d -> d.role()).containsExactly("MAKER", "CHECKER");
        assertThat(view.decisions().get(0).staffRef()).startsWith("staff:").doesNotContain(maker.toString());
        assertThat(governance.openRequestFor(subject)).isEmpty();
    }

    @Test
    void checkerCannotBeTheMakerNorTheMemberAndRolesAreEnforced() {
        UUID member = UUID.randomUUID();
        UUID request = openMakerChecker(Ids.newId(), member);

        assertThatThrownBy(() -> governance.decide(decide(request, maker, Set.of("CHECKER"), Decision.APPROVE, SNAPSHOT)))
                .hasFieldOrPropertyWithValue("errorCode.code", "decider-role-required");
        assertThatThrownBy(() -> governance.decide(new Decide(request, UUID.randomUUID(), Set.of("OFFICER"), member,
                Decision.APPROVE, "INCOME_DROP_VERIFIED", null, 30_000, SNAPSHOT, "k-" + Ids.newId())))
                .hasFieldOrPropertyWithValue("errorCode.code", "sod-violation");

        governance.decide(decide(request, maker, Set.of("OFFICER", "CHECKER"), Decision.APPROVE, SNAPSHOT));
        assertThatThrownBy(() -> governance.decide(decide(request, maker, Set.of("OFFICER", "CHECKER"), Decision.APPROVE,
                SNAPSHOT)))
                .hasFieldOrPropertyWithValue("errorCode.code", "sod-violation");
        assertThat(governance.request(request).orElseThrow().status()).isEqualTo("OPEN");
    }

    /** A bypass of the service still cannot record a checker who is the maker: the deferred trigger refuses it. */
    @Test
    void databaseEnforcesSeparationOfDuties() {
        UUID request = openMakerChecker(Ids.newId(), UUID.randomUUID());
        governance.decide(decide(request, maker, Set.of("OFFICER"), Decision.APPROVE, SNAPSHOT));

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO governance.approval (id, request_id, role, staff_id, decision, reason_code, evidence_viewed_ms,
                                                 idempotency_key)
                VALUES (:id, :r, 'CHECKER', :s, 'APPROVE', 'OK', 0, 'direct')
                """).param("id", Ids.newId()).param("r", request).param("s", maker).update())
                .hasMessageContaining("separation of duties");
        assertThatThrownBy(() -> jdbc.sql("UPDATE governance.approval SET decision = 'REJECT' WHERE request_id = :r")
                .param("r", request).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void staleSnapshotVoidsTheRequestAndTheVoidSurvives() {
        UUID subject = Ids.newId();
        UUID request = openMakerChecker(subject, UUID.randomUUID());

        assertThatThrownBy(() -> governance.decide(decide(request, maker, Set.of("OFFICER"), Decision.APPROVE, "b".repeat(64))))
                .isInstanceOf(ApprovalStaleException.class)
                .hasFieldOrPropertyWithValue("errorCode.code", "approval-stale");
        ApprovalView view = governance.request(request).orElseThrow();
        assertThat(view.status()).isEqualTo("VOIDED");
        assertThat(view.closedReason()).isEqualTo("STALE_SNAPSHOT");
        assertThat(view.decisions()).isEmpty();
        assertThat(governance.openRequestFor(subject)).isEmpty();
    }

    @Test
    void decisionsAreIdempotentAndRejectNeedsAReason() {
        UUID request = openMakerChecker(Ids.newId(), UUID.randomUUID());
        Decide reject = new Decide(request, maker, Set.of("OFFICER"), null, Decision.REJECT, "OPTION_NOT_SUSTAINABLE",
                "Income is still falling", 45_000, SNAPSHOT, "decision-1");
        DecisionResult first = governance.decide(reject);
        DecisionResult again = governance.decide(reject);
        assertThat(again).isEqualTo(first);
        assertThat(first.requestStatus()).isEqualTo("REJECTED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM governance.approval WHERE request_id = :r").param("r", request)
                .query(Integer.class).single()).isEqualTo(1);

        UUID other = openMakerChecker(Ids.newId(), UUID.randomUUID());
        assertThatThrownBy(() -> governance.decide(new Decide(other, maker, Set.of("OFFICER"), null, Decision.REJECT, " ",
                null, 1_000, SNAPSHOT, "decision-2")))
                .hasFieldOrPropertyWithValue("errorCode.code", "reason-code-required");
        assertThatThrownBy(() -> governance.decide(decide(request, checker, Set.of("CHECKER"), Decision.APPROVE, SNAPSHOT)))
                .hasFieldOrPropertyWithValue("errorCode.code", "approval-request-closed");
    }

    @Test
    void policyTierIsApprovedAtOnceAndOpenIsIdempotentPerSnapshot() {
        UUID policySubject = Ids.newId();
        UUID auto = governance.openApprovalRequest(new OpenApproval(policySubject, UUID.randomUUID(), Tier.AUTO_POLICY,
                Ids.newId(), SNAPSHOT, null, false));
        ApprovalView view = governance.request(auto).orElseThrow();
        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.closedReason()).isEqualTo("POLICY");

        UUID subject = Ids.newId();
        UUID request = openMakerChecker(subject, UUID.randomUUID());
        assertThat(openMakerChecker(subject, UUID.randomUUID())).isEqualTo(request);
        assertThatThrownBy(() -> governance.openApprovalRequest(new OpenApproval(subject, null, Tier.MAKER_CHECKER, null,
                "c".repeat(64), null, true)))
                .hasFieldOrPropertyWithValue("errorCode.code", "approval-request-open");

        governance.voidRequest(request, "WITHDRAWN");
        governance.voidRequest(request, "WITHDRAWN");
        assertThat(governance.request(request).orElseThrow().status()).isEqualTo("VOIDED");
    }

    @Test
    void recentDecisionsFeedTheRubberStampDetector() {
        UUID officer = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            UUID request = openMakerChecker(Ids.newId(), UUID.randomUUID());
            governance.decide(new Decide(request, officer, Set.of("OFFICER"), null, Decision.APPROVE, "INCOME_DROP_VERIFIED",
                    null, 2_000 + i, SNAPSHOT, "k-" + i));
        }
        var stats = governance.recentDecisions(officer, 20);
        assertThat(stats).hasSize(3).allMatch(s -> s.approve() && s.decidedMs() >= 0);
        assertThat(stats.get(0).evidenceViewedMs()).isEqualTo(2_002);
    }

    /** Editing any stored entry breaks the chain at that entry; putting it back heals it. */
    @Test
    void auditChainDetectsTamperingAndSurvivesConcurrentWriters() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Long>> writes = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                int n = i;
                writes.add(pool.submit(() -> governance.audit(new AuditCommand("TEST_ENTRY", null, "SYSTEM", "test",
                        "{\"n\":" + n + ",\"at\":\"" + Instant.now() + "\"}"))));
            }
            for (Future<Long> w : writes) {
                w.get();
            }
        } finally {
            pool.shutdown();
        }
        var intact = governance.verifyChain();
        assertThat(intact.intact()).isTrue();
        assertThat(intact.entries()).isGreaterThanOrEqualTo(80);

        long victim = jdbc.sql("SELECT MIN(seq) FROM governance.audit_entry WHERE entry_type = 'TEST_ENTRY'")
                .query(Long.class).single();
        String original = jdbc.sql("SELECT payload::text FROM governance.audit_entry WHERE seq = :s").param("s", victim)
                .query(String.class).single();
        tamper(victim, "{\"n\": -1}");
        var broken = governance.verifyChain();
        assertThat(broken.intact()).isFalse();
        assertThat(broken.firstBrokenSeq()).isEqualTo(victim);

        tamper(victim, original);
        assertThat(governance.verifyChain().intact()).isTrue();
    }

    @Test
    void verifyJobNeedsTheServiceToken() throws Exception {
        mvc.perform(post("/internal/jobs/governance/verify-audit-chain").header("Authorization", SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true));
        mvc.perform(post("/internal/jobs/governance/verify-audit-chain"))
                .andExpect(status().is4xxClientError());
    }

    private void tamper(long seq, String payload) {
        jdbc.sql("ALTER TABLE governance.audit_entry DISABLE TRIGGER audit_entry_append_only").update();
        try {
            jdbc.sql("UPDATE governance.audit_entry SET payload = CAST(:p AS jsonb) WHERE seq = :s")
                    .param("p", payload).param("s", seq).update();
        } finally {
            jdbc.sql("ALTER TABLE governance.audit_entry ENABLE TRIGGER audit_entry_append_only").update();
        }
    }

    private UUID openMakerChecker(UUID subject, UUID member) {
        return governance.openApprovalRequest(new OpenApproval(subject, member, Tier.MAKER_CHECKER, Ids.newId(), SNAPSHOT,
                Instant.now().plusSeconds(36_000), true));
    }

    private static Decide decide(UUID request, UUID staff, Set<String> roles, Decision decision, String snapshot) {
        return new Decide(request, staff, roles, null, decision, "INCOME_DROP_VERIFIED", null, 41_200, snapshot,
                "k-" + Ids.newId());
    }
}
