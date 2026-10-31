package com.paypalseed.lending.infrastructure.web;

import com.paypalseed.engines.schedule.DueDaySuggester;
import com.paypalseed.engines.schedule.DueDaySuggester.DueDaySuggestion;
import com.paypalseed.engines.schedule.MoneyEvent;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.ReschedulePreview;
import com.paypalseed.lending.api.dto.LendingDtos.ReschedulePrimitive;
import com.paypalseed.lending.api.dto.LendingDtos.RescheduleRequest;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.lending.api.dto.LendingDtos.VersionView;
import com.paypalseed.lending.application.usecases.CollectionService;
import com.paypalseed.lending.application.usecases.LoanQueries;
import com.paypalseed.lending.application.usecases.LoanQueries.TransactionView;
import com.paypalseed.lending.application.usecases.LoanRecalculator;
import com.paypalseed.lending.application.usecases.RescheduleService;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.payments.api.dto.PaymentsDtos.CapturedOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.CheckoutOrder;
import com.paypalseed.payments.api.dto.PaymentsDtos.MandateView;
import com.paypalseed.payments.api.dto.PaymentsDtos.OrderPurpose;
import com.paypalseed.payments.api.dto.PaymentsDtos.RefundResult;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/** Member routes (02-design §6.1). A member only ever sees their own loans; others are 404. */
@RestController
@RequestMapping("/api/v1")
class MemberLoanController {

    record PaymentPreviewRequest(@NotNull @Positive Long amountCents) {
    }

    record ReschedulePreviewRequest(@NotNull ReschedulePrimitive primitive, Integer seq,
                                    @Min(1) @Max(14) Integer days, @Min(1) @Max(6) Integer extraMonths) {
    }

    record StartAutopayRequest(@NotBlank @Size(max = 2048) String returnUrl, @NotBlank @Size(max = 2048) String cancelUrl) {
    }

    record ConfirmAutopayRequest(@NotBlank @Size(max = 64) String subscriptionId) {
    }

    record CreatePaymentRequest(@NotNull @Positive Long amountCents, @NotNull OrderPurpose purpose) {
    }

    record RefundRequest(@NotNull @Positive Long amountCents) {
    }

    record PayoffQuote(UUID loanId, long outstandingCents, java.time.LocalDate validOn) {
    }

    private final LoanQueries queries;
    private final LoanRecalculator recalculator;
    private final RescheduleService reschedules;
    private final CollectionService collection;
    private final BusinessClock clock;

    MemberLoanController(LoanQueries queries, LoanRecalculator recalculator, RescheduleService reschedules,
                         CollectionService collection, BusinessClock clock) {
        this.queries = queries;
        this.recalculator = recalculator;
        this.reschedules = reschedules;
        this.collection = collection;
        this.clock = clock;
    }

    @GetMapping("/loans")
    List<LoanSummary> myLoans(Actor actor) {
        return queries.loansOfMember(actor.requireMember());
    }

    @GetMapping("/loans/{loanId}/schedule")
    ResponseEntity<ScheduleView> schedule(Actor actor, @PathVariable UUID loanId,
                                          @RequestParam Optional<Integer> version, WebRequest request) {
        LoanRow loan = own(actor, loanId);
        String etag = "\"" + loan.id() + ":" + loan.rowVersion() + ":" + version.orElse(loan.currentVersion()) + ":"
                + clock.today() + "\"";
        if (request.checkNotModified(etag)) {
            return null; // Spring has already written 304 Not Modified
        }
        return ResponseEntity.ok().eTag(etag).body(queries.schedule(loan, version));
    }

    @GetMapping("/loans/{loanId}/schedule/versions")
    List<VersionView> versions(Actor actor, @PathVariable UUID loanId) {
        return queries.versions(own(actor, loanId));
    }

    @GetMapping("/loans/{loanId}/timeline")
    TimelineView timeline(Actor actor, @PathVariable UUID loanId) {
        LoanRow loan = own(actor, loanId);
        return TimelineView.of(loanId, queries.schedule(loan, Optional.empty()), queries.versions(loan),
                loan.policy().graceDays());
    }

    @GetMapping("/loans/{loanId}/transactions")
    List<TransactionView> transactions(Actor actor, @PathVariable UUID loanId) {
        return queries.transactions(own(actor, loanId));
    }

    @GetMapping("/loans/{loanId}/payoff-quote")
    PayoffQuote payoff(Actor actor, @PathVariable UUID loanId) {
        LoanRow loan = own(actor, loanId);
        return new PayoffQuote(loanId, loan.outstandingCents(), clock.today());
    }

    /** "What if I pay $X today?": the pure fold with one more receipt; nothing is written. */
    @PostMapping("/loans/{loanId}/payments/preview")
    ScheduleView previewPayment(Actor actor, @PathVariable UUID loanId, @Valid @RequestBody PaymentPreviewRequest body) {
        LoanRow loan = own(actor, loanId);
        var projected = recalculator.project(loan,
                List.of(new MoneyEvent.Receipt(Ids.newId(), clock.today(), body.amountCents())));
        return queries.view(loan, projected.schedule(), projected.allocation(), clock.today());
    }

    @PostMapping("/loans/{loanId}/schedule/preview")
    ReschedulePreview previewReschedule(Actor actor, @PathVariable UUID loanId,
                                        @Valid @RequestBody ReschedulePreviewRequest body) {
        return reschedules.preview(own(actor, loanId),
                new RescheduleRequest(loanId, body.primitive(), body.seq(), body.days(), body.extraMonths()));
    }

    @PostMapping("/loans/{loanId}/autopay")
    ResponseEntity<MandateView> startAutopay(Actor actor, @PathVariable UUID loanId,
                                             @Valid @RequestBody StartAutopayRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(collection.startAutopay(own(actor, loanId), body.returnUrl(), body.cancelUrl()));
    }

    @PostMapping("/loans/{loanId}/autopay/confirm")
    MandateView confirmAutopay(Actor actor, @PathVariable UUID loanId, @Valid @RequestBody ConfirmAutopayRequest body) {
        return collection.confirmAutopay(own(actor, loanId), body.subscriptionId());
    }

    @DeleteMapping("/loans/{loanId}/autopay")
    ResponseEntity<Void> stopAutopay(Actor actor, @PathVariable UUID loanId) {
        collection.stopAutopay(own(actor, loanId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/loans/{loanId}/payments")
    ResponseEntity<CheckoutOrder> createPayment(Actor actor, @PathVariable UUID loanId,
                                                @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                                                @Valid @RequestBody CreatePaymentRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(collection.createPayment(own(actor, loanId), body.amountCents(), body.purpose(), key));
    }

    @PostMapping("/loans/{loanId}/payments/{orderId}/capture")
    CapturedOrder capture(Actor actor, @PathVariable UUID loanId, @PathVariable @Size(max = 64) String orderId) {
        return collection.capturePayment(own(actor, loanId), orderId);
    }

    @PostMapping("/loans/{loanId}/overpayment-refunds")
    ResponseEntity<RefundResult> refund(Actor actor, @PathVariable UUID loanId,
                                        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                                        @Valid @RequestBody RefundRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(collection.refundOverpayment(own(actor, loanId), body.amountCents(), key));
    }

    /** FR-02. No F3 income profile is available yet, so the suggestion uses only the stated payday. */
    @GetMapping("/members/me/due-day-suggestion")
    DueDaySuggestion dueDaySuggestion(Actor actor, @RequestParam Optional<Integer> statedPayday) {
        actor.requireMember();
        OptionalInt payday = statedPayday.filter(d -> d >= 1 && d <= 31).map(OptionalInt::of).orElse(OptionalInt.empty());
        return DueDaySuggester.suggest(Optional.empty(), payday);
    }

    private LoanRow own(Actor actor, UUID loanId) {
        return queries.loanOfMember(loanId, actor.requireMember());
    }
}
