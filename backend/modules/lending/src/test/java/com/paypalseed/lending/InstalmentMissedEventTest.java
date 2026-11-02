package com.paypalseed.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.lending.api.events.LendingEvents.InstalmentMissed;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InstalmentMissedEventTest {

    private static final LocalDate DUE = LocalDate.of(2026, 12, 20);

    @Test
    void aMissCanSayOurOwnLateDecisionCausedIt() {
        InstalmentMissed e = new InstalmentMissed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), DUE.plusDays(6), 1, 2,
            DUE, "SLA_BREACH");

        assertThat(e.attribution()).isEqualTo("SLA_BREACH");
    }

    @Test
    void theShapeBeforeAttributionStillBuildsAndSaysNothingAboutWhoseMissItWas() {
        InstalmentMissed e = new InstalmentMissed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), DUE.plusDays(6), 1, 2, DUE);

        assertThat(e.attribution()).isNull();
        assertThat(e.seq()).isEqualTo(2);
    }
}
