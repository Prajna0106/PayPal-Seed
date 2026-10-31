package com.paypalseed.hardship.application.usecases;

import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.CaseStatus;
import com.paypalseed.hardship.infrastructure.persistence.CaseRecordsRepository;
import com.paypalseed.hardship.infrastructure.persistence.CaseRecordsRepository.DueTimer;
import com.paypalseed.hardship.infrastructure.persistence.CaseRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fires due case timers (03 §10): agent deadline, offer reminder and expiry, hold check and expiry, SLA
 * escalations and saga retries. Each timer fires in its own transaction under a SKIP LOCKED claim, so parallel
 * sweepers never fire one twice, and a failing timer is postponed instead of blocking the others.
 */
@Service
public class CaseTimers {

    private static final Logger log = LoggerFactory.getLogger(CaseTimers.class);
    private static final Duration POSTPONE = Duration.ofMinutes(5);

    private final CaseRecordsRepository records;
    private final CaseRepository cases;
    private final OutreachService outreach;
    private final ApprovalFlow approvals;
    private final CaseLifecycle lifecycle;
    private final BusinessClock clock;
    private final TransactionTemplate tx;

    public CaseTimers(CaseRecordsRepository records, CaseRepository cases, OutreachService outreach, ApprovalFlow approvals,
                      CaseLifecycle lifecycle, BusinessClock clock, PlatformTransactionManager txManager) {
        this.records = records;
        this.cases = cases;
        this.outreach = outreach;
        this.approvals = approvals;
        this.lifecycle = lifecycle;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /** Fires up to {@code limit} due timers; returns how many fired. */
    public int sweep(int limit) {
        Instant now = clock.now().isAfter(Instant.now()) ? clock.now() : Instant.now();
        int fired = 0;
        while (fired < limit) {
            Optional<DueTimer> timer;
            try {
                timer = tx.execute(status -> {
                    List<DueTimer> due = records.claimDue(now, 1);
                    if (due.isEmpty()) {
                        return Optional.<DueTimer>empty();
                    }
                    DueTimer t = due.get(0);
                    records.markFired(t.caseId(), t.kind());
                    fire(t);
                    return Optional.of(t);
                });
            } catch (RuntimeException e) {
                postponeFailed(now, e);
                continue;
            }
            if (timer == null || timer.isEmpty()) {
                break;
            }
            fired++;
            if ("SAGA_RETRY".equals(timer.get().kind())) {
                approvals.execute(timer.get().caseId());
            }
        }
        return fired;
    }

    private void fire(DueTimer t) {
        Optional<CaseRow> found = cases.lock(t.caseId());
        if (found.isEmpty() || found.get().status().terminal()) {
            return;
        }
        CaseRow c = found.get();
        switch (t.kind()) {
            case "AGENT_DEADLINE" -> outreach.agentDeadlinePassed(c.id());
            case "OFFER_REMINDER" -> outreach.issue(c.id(), true);
            case "OFFER_EXPIRY" -> {
                if (c.status().awaitsMember()) {
                    lifecycle.close(c, CaseStatus.EXPIRED, "NO_REPLY");
                }
            }
            case "HOLD_CHECK" -> approvals.placeHoldIfDue(c);
            case "SLA_50", "SLA_80" -> {
                if (c.status() == CaseStatus.PENDING_APPROVAL) {
                    lifecycle.audit("HARDSHIP_" + t.kind(), c, "SYSTEM", "hardship", Map.of("dueAt", t.fireAt().toString()));
                    log.warn("Hardship case {} reached {} of its decision SLA", c.id(), t.kind());
                }
            }
            case "SLA_BREACH" -> approvals.slaBreached(c);
            case "HOLD_EXPIRY" -> lifecycle.audit("HARDSHIP_HOLD_ENDED", c, "SYSTEM", "hardship", Map.of("status", c.status().name()));
            case "SAGA_RETRY" -> {
                // executed after this transaction commits, in the saga's own transaction
            }
            default -> log.warn("Unknown case timer {} on {}", t.kind(), t.caseId());
        }
    }

    /** The timer that threw is left due but moved back, so one bad case cannot stall the sweep. */
    private void postponeFailed(Instant now, RuntimeException e) {
        log.warn("A case timer failed: {}", e.toString());
        tx.executeWithoutResult(status -> {
            List<DueTimer> due = records.claimDue(now, 1);
            if (!due.isEmpty()) {
                DueTimer t = due.get(0);
                records.schedule(t.caseId(), t.kind(), now.plus(POSTPONE));
            }
        });
    }
}
