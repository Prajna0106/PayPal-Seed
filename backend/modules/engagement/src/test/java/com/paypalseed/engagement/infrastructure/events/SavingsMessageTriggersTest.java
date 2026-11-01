package com.paypalseed.engagement.infrastructure.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.paypalseed.engagement.application.usecases.ReminderService;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.events.WithdrawalFailedEvent;
import com.paypalseed.savings.api.events.WithdrawalReturnedEvent;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SavingsMessageTriggersTest {

    private final ReminderService reminders = mock(ReminderService.class);
    private final SavingsMessageTriggers triggers = new SavingsMessageTriggers(reminders);

    @SuppressWarnings("unchecked")
    private final ArgumentCaptor<Function<String, String>> body = ArgumentCaptor.forClass(Function.class);

    @Test
    void aFailedWithdrawalIsKeyedByTheWithdrawalSoARedeliveryDoesNotTextTwice() {
        UUID member = UUID.randomUUID();
        UUID goal = UUID.randomUUID();
        UUID withdrawal = UUID.randomUUID();

        triggers.on(new WithdrawalFailedEvent(UUID.randomUUID(), member.toString(), goal, withdrawal, Money.ofCents(2_500), "RECEIVER_UNREGISTERED",
                java.time.Instant.parse("2026-11-10T12:00:00Z")));

        verify(reminders).notifyAboutGoal(eq(member), eq(goal), eq("WITHDRAWAL_FAILED"), eq("withdrawal:" + withdrawal + ":failed"), body.capture());
        assertThat(body.getValue().apply("en")).contains("$25").contains("back in your goal").doesNotContain("RECEIVER_UNREGISTERED");
    }

    @Test
    void aReturnedWithdrawalHasItsOwnKeyAndText() {
        UUID member = UUID.randomUUID();
        UUID goal = UUID.randomUUID();
        UUID withdrawal = UUID.randomUUID();

        triggers.on(new WithdrawalReturnedEvent(UUID.randomUUID(), member.toString(), goal, withdrawal, Money.ofCents(1_000), "UNCLAIMED",
                java.time.Instant.parse("2026-11-10T12:00:00Z")));

        verify(reminders).notifyAboutGoal(eq(member), eq(goal), eq("WITHDRAWAL_RETURNED"), eq("withdrawal:" + withdrawal + ":returned"), body.capture());
        assertThat(body.getValue().apply("es")).contains("$10").contains("devolvió");
    }

    @Test
    void aMemberIdThatIsNotAUuidMapsToTheStableUuidSavingsUses() {
        String legacy = "member-" + UUID.randomUUID();
        UUID mapped = UUID.nameUUIDFromBytes(legacy.getBytes(StandardCharsets.UTF_8));

        triggers.on(new WithdrawalFailedEvent(UUID.randomUUID(), legacy, UUID.randomUUID(), UUID.randomUUID(), Money.ofCents(500), "X",
                java.time.Instant.parse("2026-11-10T12:00:00Z")));

        verify(reminders).notifyAboutGoal(eq(mapped), any(), any(), any(), any());
    }
}
