package com.paypalseed.underwriting.infrastructure.web;

import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import com.paypalseed.underwriting.application.usecases.IncomeSummaryService;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Week;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-only seeding of consented income for demos and sandboxes. With {@code seed.underwriting.income.source=
 * TRANSACTION_SEARCH} the weeks come from PayPal instead and a seeded set is replaced on the next refresh (ADR 0018).
 */
@RestController
class UnderwritingInternalController {

    record WeekInput(@NotNull LocalDate weekStart, @NotNull @PositiveOrZero Long inflowCents) {
    }

    record IncomeInput(@NotNull Boolean consent, @Min(1) @Max(7) Integer usualPaydayDow,
                       @Size(max = 104) List<@Valid WeekInput> weeks) {
    }

    private final IncomeSummaryService income;
    private final BusinessClock clock;

    UnderwritingInternalController(IncomeSummaryService income, BusinessClock clock) {
        this.income = income;
        this.clock = clock;
    }

    /** Returns the summary as of today; empty (no body) when consent was revoked. */
    @PutMapping("/internal/underwriting/members/{memberId}/income")
    Optional<IncomeSummary> record(Actor actor, @PathVariable UUID memberId, @Valid @RequestBody IncomeInput body) {
        actor.requireAnyRole(Role.SERVICE);
        List<Week> weeks = body.weeks() == null ? List.of()
                : body.weeks().stream().map(w -> new Week(w.weekStart(), w.inflowCents())).toList();
        income.record(memberId, body.consent(), body.usualPaydayDow(), weeks);
        return income.incomeSummary(memberId, clock.today());
    }
}
