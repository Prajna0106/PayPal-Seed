package com.paypalseed.deskquery.infrastructure.events;

import com.paypalseed.deskquery.application.usecases.ActivityProjector;
import com.paypalseed.lending.api.events.LendingEvents.LoanVoided;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReceived;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReversed;
import com.paypalseed.lending.api.events.LendingEvents.ScheduleGenerated;
import com.paypalseed.savings.api.events.DepositReceivedEvent;
import com.paypalseed.savings.api.events.WithdrawalProcessedEvent;
import com.paypalseed.savings.api.events.WithdrawalReturnedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The member activity list's inputs. Kept apart from {@link DeskProjectionListeners} (the desk grids) because it serves
 * the member's Home page and has its own rebuild. Same delivery rules: at least once, so every handler is a keyed insert.
 */
@Component
class ActivityListeners {

    private final ActivityProjector activity;

    ActivityListeners(ActivityProjector activity) {
        this.activity = activity;
    }

    @ApplicationModuleListener
    void on(DepositReceivedEvent e) {
        activity.on(e);
    }

    @ApplicationModuleListener
    void on(WithdrawalProcessedEvent e) {
        activity.on(e);
    }

    @ApplicationModuleListener
    void on(WithdrawalReturnedEvent e) {
        activity.on(e);
    }

    @ApplicationModuleListener
    void on(ScheduleGenerated e) {
        activity.on(e);
    }

    @ApplicationModuleListener
    void on(RepaymentReceived e) {
        activity.on(e);
    }

    @ApplicationModuleListener
    void on(RepaymentReversed e) {
        activity.on(e);
    }

    @ApplicationModuleListener
    void on(LoanVoided e) {
        activity.on(e);
    }
}
