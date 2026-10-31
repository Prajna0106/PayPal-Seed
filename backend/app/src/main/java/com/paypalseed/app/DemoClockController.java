package com.paypalseed.app;

import com.paypalseed.platform.persistence.DatabaseBusinessClock;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo only ({@code seed.clock.mode=database}): moves the shared business date forward so months
 * pass in seconds. Callers then run {@code POST /internal/jobs/cob/catch-up} to close every
 * skipped date in order.
 */
@RestController
@ConditionalOnProperty(name = "seed.clock.mode", havingValue = "database")
class DemoClockController {

    record Advanced(LocalDate businessDate) {
    }

    private final DatabaseBusinessClock clock;

    DemoClockController(DatabaseBusinessClock clock) {
        this.clock = clock;
    }

    @PostMapping("/internal/demo-clock/advance")
    Advanced advance(Actor actor, @RequestParam @Min(1) @Max(400) int days) {
        actor.requireAnyRole(Role.SERVICE);
        return new Advanced(clock.advance(days));
    }
}
