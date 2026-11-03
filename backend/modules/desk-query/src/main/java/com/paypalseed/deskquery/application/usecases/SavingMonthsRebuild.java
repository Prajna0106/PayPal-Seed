package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.domain.model.MemberRefs;
import com.paypalseed.deskquery.domain.model.SavingStreak;
import com.paypalseed.deskquery.infrastructure.persistence.ApplicationViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.RebuildMarkerRepository;
import com.paypalseed.deskquery.infrastructure.persistence.SavingsViewRepository;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.DepositFact;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings the saving months of every member to the business time zone (ADR 0027). Months stored before that decision were
 * cut in UTC, so a deposit on the evening of a month's last day counted for the next month, and the event that carried it
 * cannot be asked again for its month once the log is trimmed. The deposits themselves are in savings with the instant they
 * were credited, so the rebuild reads them from there and applies {@link SavingStreak#monthOf} to each.
 *
 * <p>One member is rebuilt per transaction, under the lock the deposit listener takes, and reads that member's deposits
 * inside it. A deposit credited while the rebuild runs is either read by it or added afterwards by its own event, in the
 * same month by the same rule, so nothing is lost and nothing is counted twice. A member with no credited deposit in
 * savings (only the development-only legacy top-up route makes one) is left as it was stored. The same pass records each
 * deposit in the member's activity list, which started after some deposits were made.
 */
@Service
public class SavingMonthsRebuild {

    /** The marker of the one-time run at start-up. */
    public static final String NAME = "saving-months-business-zone";
    private static final int MEMBER_PAGE = 200;
    private static final Logger log = LoggerFactory.getLogger(SavingMonthsRebuild.class);

    private final SavingsFacade savings;
    private final SavingsViewRepository savingsView;
    private final ApplicationViewRepository applications;
    private final ActivityProjector activity;
    private final RebuildMarkerRepository markers;
    private final TransactionTemplate tx;

    SavingMonthsRebuild(SavingsFacade savings, SavingsViewRepository savingsView, ApplicationViewRepository applications,
                        ActivityProjector activity, RebuildMarkerRepository markers, PlatformTransactionManager transactions) {
        this.savings = savings;
        this.savingsView = savingsView;
        this.applications = applications;
        this.activity = activity;
        this.markers = markers;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Rebuilds every member, whatever the marker says, and returns how many had deposits. */
    public int rebuildAll() {
        int rebuilt = 0;
        String after = null;
        while (true) {
            List<String> page = savings.membersWithDeposits(after, MEMBER_PAGE);
            if (page.isEmpty()) {
                return rebuilt;
            }
            for (String member : page) {
                if (Boolean.TRUE.equals(tx.execute(status -> rebuildMember(member)))) {
                    rebuilt++;
                }
            }
            after = page.get(page.size() - 1);
        }
    }

    /**
     * The start-up run: does nothing when the marker says it was done, and writes the marker only after every member was
     * rebuilt, so a run that failed half way is tried again at the next start. Returns whether it ran.
     */
    public boolean runOnce() {
        if (markers.isDone(NAME)) {
            return false;
        }
        int members = rebuildAll();
        tx.executeWithoutResult(status -> markers.markDone(NAME));
        log.info("Rebuilt the saving months of {} members in the business time zone", members);
        return true;
    }

    private boolean rebuildMember(String savingsMemberId) {
        List<DepositFact> deposits = savings.depositsOfMember(savingsMemberId);
        if (deposits.isEmpty()) {
            return false;
        }
        UUID member = MemberRefs.ofSavings(savingsMemberId);
        applications.lockMember(member);
        Set<LocalDate> months = new TreeSet<>();
        for (DepositFact d : deposits) {
            months.add(SavingStreak.monthOf(d.creditedAt()));
            activity.deposit(ActivityProjector.depositKey(d.depositKey()), savingsMemberId, d.goalId(), d.amountCents(), d.creditedAt());
        }
        savingsView.replaceMonths(member, months);
        int streak = SavingStreak.of(months);
        savingsView.setStreak(member, streak);
        applications.setSavingStreak(member, streak);
        return true;
    }
}
