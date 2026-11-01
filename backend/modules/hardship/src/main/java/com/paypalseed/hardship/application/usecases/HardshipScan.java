package com.paypalseed.hardship.application.usecases;

import com.paypalseed.engines.rules.hardship.HardshipPolicy;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.platform.kernel.BusinessClock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily scan (02-design §4.1): loans whose oldest owed instalment is due in the next 10 days or is in grace,
 * one shard group at a time with keyset pages, each loan scored and possibly opened in its own transaction so
 * one bad loan never stops the rest. Re-running a page is harmless: runs and cases are unique per day and loan.
 */
@Service
public class HardshipScan {

    private static final Logger log = LoggerFactory.getLogger(HardshipScan.class);

    public record PageResult(LocalDate businessDate, int scanned, int opened, int failed, UUID nextAfter, boolean done) {
    }

    private final LendingFacade lending;
    private final RiskAssessor assessor;
    private final CaseOpening opening;
    private final HardshipSettings settings;
    private final BusinessClock clock;
    private final TransactionTemplate perLoan;

    public HardshipScan(LendingFacade lending, RiskAssessor assessor, CaseOpening opening, HardshipSettings settings,
                        BusinessClock clock, PlatformTransactionManager txManager) {
        this.lending = lending;
        this.assessor = assessor;
        this.opening = opening;
        this.settings = settings;
        this.clock = clock;
        this.perLoan = new TransactionTemplate(txManager);
        this.perLoan.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    public PageResult scanPage(LocalDate date, int shardGroup, int shardCount, UUID after, int limit) {
        LocalDate businessDate = date == null ? clock.today() : date;
        if (businessDate.isAfter(clock.today())) {
            throw new IllegalArgumentException("cannot scan a future business date");
        }
        HardshipPolicy.Scoring s = settings.policy().scoring();
        List<UUID> loans = lending.hardshipScanCandidates(businessDate, s.scanLeadDays(), s.graceDays(), shardGroup, shardCount,
                after, limit);
        int opened = 0;
        int failed = 0;
        for (UUID loanId : loans) {
            try {
                if (scanLoan(loanId, businessDate)) {
                    opened++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Hardship scan of loan {} failed: {}", loanId, e.toString());
            }
        }
        return new PageResult(businessDate, loans.size(), opened, failed, loans.isEmpty() ? after : loans.get(loans.size() - 1),
                loans.size() < limit);
    }

    /** Every shard group to the end, for the worker-role scheduler fallback. Returns the number of cases opened. */
    public int scanAll(LocalDate date, int shardCount, int pageSize) {
        int opened = 0;
        for (int group = 0; group < shardCount; group++) {
            UUID after = null;
            PageResult page;
            do {
                page = scanPage(date, group, shardCount, after, pageSize);
                opened += page.opened();
                after = page.nextAfter();
            } while (!page.done());
        }
        return opened;
    }

    /**
     * An event wake-up. Events can be delivered late, so the loan is scored as of today, never as of the event's
     * date: a stale date could open a case for an instalment already paid.
     */
    public boolean wake(UUID loanId) {
        return scanLoan(loanId, clock.today());
    }

    /** Scores one loan and opens a case when the policy says so. */
    public boolean scanLoan(UUID loanId, LocalDate date) {
        Boolean opened = perLoan.execute(status -> assessor.assess(loanId, date, false)
                .map(a -> opening.openProactive(a).opened().isPresent())
                .orElse(false));
        return Boolean.TRUE.equals(opened);
    }
}
