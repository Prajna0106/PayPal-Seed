package com.paypalseed.hardship.infrastructure.web;

import com.paypalseed.hardship.application.usecases.MemberDecisions;
import com.paypalseed.hardship.application.usecases.MemberDecisions.ChoiceResult;
import com.paypalseed.hardship.application.usecases.MemberDecisions.MemberCase;
import com.paypalseed.hardship.domain.model.OfferModel.OptionRow;
import com.paypalseed.platform.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Member routes (02-design §6.1). Every number shown comes from the engine preview; {@code message.source} says
 * whether a model wrote the paragraph. The risk level and score are never shown to the member.
 */
@RestController
@Validated
class MemberHardshipController {

    record HelpRequest(@NotNull UUID loanId, @Size(max = 500) String reasonText) {
    }

    record ChoiceRequest(@NotNull UUID offerId, @NotNull UUID optionId) {
    }

    record Target(int seq, LocalDate dueDate, long amountCents) {
    }

    record Message(String locale, String source, String text) {
    }

    record Option(UUID optionId, String label, String primitive, Map<String, Integer> args, Map<String, Long> summary,
                  String tier, Boolean sustainable, boolean recommended, List<String> warnings) {
    }

    record Offer(UUID offerId, int revision, LocalDate expiresOn, String recommended, Message message, List<Option> options) {
    }

    record HardshipView(UUID caseId, String status, Target target, Offer offer) {
    }

    record ChoiceView(UUID caseId, String status, String optionLabel, String tier, Instant decisionDueAt, LocalDate holdUntil,
                      Long payNowCents) {
    }

    record DeclineView(UUID caseId, String status) {
    }

    private final MemberDecisions members;

    MemberHardshipController(MemberDecisions members) {
        this.members = members;
    }

    @GetMapping("/api/v1/loans/{loanId}/hardship")
    HardshipView offer(Actor actor, @PathVariable UUID loanId, @RequestParam(defaultValue = "en") String lang) {
        return view(members.view(loanId, actor.requireMember()), lang);
    }

    @PostMapping("/api/v1/hardship/cases")
    ResponseEntity<HardshipView> askForHelp(Actor actor, @Valid @RequestBody HelpRequest body,
                                            @RequestParam(defaultValue = "en") String lang) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(view(members.askForHelp(body.loanId(), actor.requireMember(), body.reasonText()), lang));
    }

    @PostMapping("/api/v1/hardship/cases/{caseId}/choice")
    ChoiceView choose(Actor actor, @PathVariable UUID caseId, @Valid @RequestBody ChoiceRequest body,
                      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        ChoiceResult r = members.choose(caseId, actor.requireMember(), body.offerId(), body.optionId(), "APP", "app:" + key);
        return new ChoiceView(r.caseId(), r.status().name(), r.optionLabel(), r.tier(), r.decisionDueAt(), r.holdUntil(),
                r.payNowCents());
    }

    @PostMapping("/api/v1/hardship/cases/{caseId}/decline")
    DeclineView decline(Actor actor, @PathVariable UUID caseId,
                        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        return new DeclineView(caseId, members.decline(caseId, actor.requireMember(), "APP", "app:" + key).name());
    }

    private static HardshipView view(MemberCase m, String lang) {
        String locale = "es".equals(lang) ? "es" : "en";
        Offer offer = m.offer().map(o -> new Offer(o.id(), o.revision(), o.expiresOn(), o.recommended(),
                new Message(locale, m.messageSource(), m.messages().get(locale)),
                m.options().stream().map(MemberHardshipController::option).toList())).orElse(null);
        return new HardshipView(m.c().id(), m.c().status().name(),
                new Target(m.c().targetSeq(), m.c().targetDueDate(), m.c().targetAmountCents()), offer);
    }

    private static Option option(OptionRow o) {
        return new Option(o.id(), o.label(), o.primitive(), o.args(), o.summary(), o.tier(), o.sustainable(), o.recommended(),
                o.warnings());
    }
}
