package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.domain.model.MemberRefs;
import com.paypalseed.deskquery.domain.model.SavingStreak;
import com.paypalseed.deskquery.infrastructure.persistence.ApplicationViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.SavingsViewRepository;
import com.paypalseed.savings.api.events.DepositReceivedEvent;
import com.paypalseed.savings.api.events.GoalCreatedEvent;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saving streaks from deposits. The set of deposit months is the stored fact and the streak is recomputed from it each time,
 * which makes the result independent of the order and the number of deliveries.
 */
@Service
public class SavingsProjector {

    private final SavingsViewRepository savings;
    private final ApplicationViewRepository applications;

    SavingsProjector(SavingsViewRepository savings, ApplicationViewRepository applications) {
        this.savings = savings;
        this.applications = applications;
    }

    @Transactional
    public void on(GoalCreatedEvent e) {
        savings.ensureMember(MemberRefs.ofSavings(e.memberId()));
    }

    @Transactional
    public void on(DepositReceivedEvent e) {
        UUID member = MemberRefs.ofSavings(e.memberId());
        applications.lockMember(member);
        savings.addMonth(member, SavingStreak.monthOf(e.timestamp()));
        int streak = SavingStreak.of(savings.months(member));
        savings.setStreak(member, streak);
        applications.setSavingStreak(member, streak);
    }
}
