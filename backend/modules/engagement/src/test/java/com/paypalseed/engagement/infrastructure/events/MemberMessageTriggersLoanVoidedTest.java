package com.paypalseed.engagement.infrastructure.events;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.paypalseed.engagement.application.usecases.ReminderService;
import com.paypalseed.lending.api.events.LendingEvents.LoanVoided;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberMessageTriggersLoanVoidedTest {

    private final ReminderService reminders = mock(ReminderService.class);
    private final MemberMessageTriggers triggers = new MemberMessageTriggers(reminders);

    @Test
    void aReturnedPayoutIsToldWithTheAmountThatIsComingBack() {
        UUID member = UUID.randomUUID();
        UUID loan = UUID.randomUUID();

        triggers.on(new LoanVoided(UUID.randomUUID(), loan, member, LocalDate.of(2026, 11, 12), UUID.randomUUID(), "APP-a1", "RETURNED",
                120_000, 15_000, 0));

        verify(reminders).loanVoided(eq(member), eq(loan), eq(15_000L), eq(false));
    }

    @Test
    void aCoolingOffCancellationIsConfirmedNotApologisedFor() {
        UUID member = UUID.randomUUID();
        UUID loan = UUID.randomUUID();

        triggers.on(new LoanVoided(UUID.randomUUID(), loan, member, LocalDate.of(2026, 11, 12), UUID.randomUUID(), "APP",
                LoanVoided.COOLING_OFF_CANCELLED, 120_000, 0, 0));

        verify(reminders).loanVoided(eq(member), eq(loan), eq(0L), eq(true));
    }
}
