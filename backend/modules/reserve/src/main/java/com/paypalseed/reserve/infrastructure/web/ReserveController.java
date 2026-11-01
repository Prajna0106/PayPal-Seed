package com.paypalseed.reserve.infrastructure.web;

import com.paypalseed.governance.api.dto.GovernanceDtos.Decision;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import com.paypalseed.reserve.api.ReserveFacade;
import com.paypalseed.reserve.api.dto.ReserveDtos.CoverDecisionResult;
import com.paypalseed.reserve.api.dto.ReserveDtos.CoverRequestPage;
import com.paypalseed.reserve.api.dto.ReserveDtos.CoverRequestView;
import com.paypalseed.reserve.api.dto.ReserveDtos.CoverStatus;
import com.paypalseed.reserve.api.dto.ReserveDtos.CreateCover;
import com.paypalseed.reserve.api.dto.ReserveDtos.DonationIntent;
import com.paypalseed.reserve.api.dto.ReserveDtos.ReserveSummary;
import com.paypalseed.reserve.application.usecases.CoverService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public face of the fund (summary, donation intent) and the officer desk for cover requests. The public routes take
 * no {@link Actor}: they are open on purpose and carry no personal data. Everything under {@code /desk} checks a role.
 */
@RestController
@Validated
class ReserveController {

    private static final String CODE = "^[A-Z][A-Z0-9_]{1,63}$";

    record IntentRequest(Boolean anonymous, @Size(max = 40) String displayName) {
    }

    record CoverBody(@NotNull UUID memberId, @NotNull UUID loanId, @NotNull @Min(1) Long amountCents,
                     @NotNull @Pattern(regexp = CODE) String reasonCode, UUID hardshipCaseId, @Size(max = 500) String note) {
    }

    record DecisionBody(@NotNull Decision decision, @Pattern(regexp = CODE) String reasonCode, @Size(max = 500) String note,
                        @NotNull @PositiveOrZero Long evidenceViewedMs) {
    }

    private final ReserveFacade reserve;
    private final CoverService covers;

    ReserveController(ReserveFacade reserve, CoverService covers) {
        this.reserve = reserve;
        this.covers = covers;
    }

    @GetMapping("/api/v1/reserve/summary")
    ResponseEntity<ReserveSummary> summary() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic()).body(reserve.summary());
    }

    @PostMapping("/api/v1/reserve/donations/intent")
    @ResponseStatus(HttpStatus.CREATED)
    DonationIntent intent(@Valid @RequestBody IntentRequest body,
                          @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return reserve.createDonationIntent(key, body.anonymous() == null || body.anonymous(), body.displayName());
    }

    @PostMapping("/api/v1/desk/reserve/cover-requests")
    @ResponseStatus(HttpStatus.CREATED)
    CoverRequestView create(Actor actor, @Valid @RequestBody CoverBody body,
                            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        actor.requireAnyRole(Role.OFFICER, Role.ADMIN);
        return covers.create(actor.id(), new CreateCover(body.memberId(), body.loanId(), body.amountCents(), body.reasonCode(),
                body.hardshipCaseId(), body.note()), key);
    }

    @GetMapping("/api/v1/desk/reserve/cover-requests")
    CoverRequestPage list(Actor actor, @RequestParam(required = false) List<CoverStatus> status,
                          @RequestParam(required = false) UUID before, @RequestParam(defaultValue = "50") int limit) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
        return covers.page(status == null ? List.of() : status, before, limit);
    }

    /** The maker's or the checker's decision; which one follows from the approval request. */
    @PostMapping("/api/v1/desk/reserve/cover-requests/{coverRequestId}/decision")
    CoverDecisionResult decide(Actor actor, @PathVariable UUID coverRequestId, @Valid @RequestBody DecisionBody body,
                               @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER);
        return covers.decide(coverRequestId, actor.id(), rolesOf(actor), body.decision(), body.reasonCode(), body.note(),
                body.evidenceViewedMs(), key);
    }

    private static Set<String> rolesOf(Actor actor) {
        return actor.roles().stream().map(Enum::name).collect(Collectors.toSet());
    }
}
