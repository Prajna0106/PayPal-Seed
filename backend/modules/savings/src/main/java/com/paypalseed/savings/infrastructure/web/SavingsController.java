package com.paypalseed.savings.infrastructure.web;

import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.ActorAuthentication;
import com.paypalseed.savings.api.dto.ChangeAmountRequest;
import com.paypalseed.savings.api.dto.CoachSuggestionRequest;
import com.paypalseed.savings.api.dto.CoachSuggestionResponse;
import com.paypalseed.savings.api.dto.CreateGoalRequest;
import com.paypalseed.savings.api.dto.SavingsGoalResponse;
import com.paypalseed.savings.api.dto.TopUpCaptureResponse;
import com.paypalseed.savings.api.dto.TopUpOrderResponse;
import com.paypalseed.savings.api.dto.TopUpRequest;
import com.paypalseed.savings.api.dto.WithdrawRequest;
import com.paypalseed.savings.api.dto.WithdrawalResponse;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.application.ports.SavingsPayments.Order;
import com.paypalseed.savings.application.ports.SavingsPayments.OrderCapture;
import com.paypalseed.savings.application.ports.WithdrawalStore;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.ManageGoalUseCase;
import com.paypalseed.savings.application.usecases.PauseResumeGoalUseCase;
import com.paypalseed.savings.application.usecases.ProcessWithdrawalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.application.usecases.StartGoalUseCase;
import com.paypalseed.savings.application.usecases.SuggestSavingsPlanUseCase;
import com.paypalseed.savings.application.usecases.TopUpUseCase;
import com.paypalseed.savings.application.usecases.WithdrawUseCase;
import com.paypalseed.savings.domain.model.SavingsErrors;
import com.paypalseed.savings.domain.model.SavingsGoal;
import com.paypalseed.savings.domain.model.SavingsWallet;
import com.paypalseed.savings.domain.model.Withdrawal;
import com.paypalseed.savings.domain.model.WithdrawalStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Savings goals. Money moves through PayPal: the backend creates the subscription (the response carries the
 * approval URL), top-ups are PayPal or Venmo orders, and a withdrawal reserves the money and pays it out.
 *
 * <p>The signed-in member is the only one who can see or change their goals; another member's goal is a 404.
 * The routes that take a client made PayPal id ({@code payPalSubscriptionId}, {@code payPalPayoutId},
 * {@code payPalCaptureId}) are legacy: they exist for development, test and demo, and are refused elsewhere.
 * Without the payments wiring or the sign-in component (a controller built by hand in a unit test) the legacy
 * behaviour is all there is and nobody is checked.
 */
@RestController
@RequestMapping("/api/v1/savings")
public class SavingsController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CreateGoalUseCase createGoalUseCase;
    private final RecordDepositUseCase recordDepositUseCase;
    private final ProcessWithdrawalUseCase processWithdrawalUseCase;
    private final PauseResumeGoalUseCase pauseResumeGoalUseCase;
    private final SavingsGoalRepository repository;
    private final SuggestSavingsPlanUseCase suggestSavingsPlanUseCase;
    private final StartGoalUseCase startGoal;
    private final ManageGoalUseCase manageGoal;
    private final TopUpUseCase topUp;
    private final WithdrawUseCase withdraw;
    private final WithdrawalStore withdrawals;
    private final ActorAuthentication authentication;
    private final boolean legacyAllowed;

    public SavingsController(
        CreateGoalUseCase createGoalUseCase,
        RecordDepositUseCase recordDepositUseCase,
        ProcessWithdrawalUseCase processWithdrawalUseCase,
        PauseResumeGoalUseCase pauseResumeGoalUseCase,
        SavingsGoalRepository repository
    ) {
        this(createGoalUseCase, recordDepositUseCase, processWithdrawalUseCase, pauseResumeGoalUseCase, repository,
            new SuggestSavingsPlanUseCase(), null, null, null, null, null, null, null);
    }

    @Autowired
    public SavingsController(
        CreateGoalUseCase createGoalUseCase,
        RecordDepositUseCase recordDepositUseCase,
        ProcessWithdrawalUseCase processWithdrawalUseCase,
        PauseResumeGoalUseCase pauseResumeGoalUseCase,
        SavingsGoalRepository repository,
        @Autowired(required = false) SuggestSavingsPlanUseCase suggestSavingsPlanUseCase,
        @Autowired(required = false) StartGoalUseCase startGoal,
        @Autowired(required = false) ManageGoalUseCase manageGoal,
        @Autowired(required = false) TopUpUseCase topUp,
        @Autowired(required = false) WithdrawUseCase withdraw,
        @Autowired(required = false) WithdrawalStore withdrawals,
        @Autowired(required = false) ActorAuthentication authentication,
        @Autowired(required = false) Environment environment
    ) {
        this.createGoalUseCase = createGoalUseCase;
        this.recordDepositUseCase = recordDepositUseCase;
        this.processWithdrawalUseCase = processWithdrawalUseCase;
        this.pauseResumeGoalUseCase = pauseResumeGoalUseCase;
        this.repository = repository;
        this.suggestSavingsPlanUseCase = suggestSavingsPlanUseCase != null
            ? suggestSavingsPlanUseCase
            : new SuggestSavingsPlanUseCase();
        this.startGoal = startGoal;
        this.manageGoal = manageGoal;
        this.topUp = topUp;
        this.withdraw = withdraw;
        this.withdrawals = withdrawals;
        this.authentication = authentication;
        this.legacyAllowed = environment == null || environment.acceptsProfiles(Profiles.of("dev", "test", "demo"));
    }

    @PostMapping("/coach/suggest")
    public ResponseEntity<CoachSuggestionResponse> suggestPlan(@RequestBody CoachSuggestionRequest request) {
        return ResponseEntity.ok(suggestSavingsPlanUseCase.execute(request));
    }

    @PostMapping("/goals")
    public ResponseEntity<SavingsGoalResponse> createGoal(
        @RequestBody CreateGoalRequest request,
        @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
        HttpServletRequest http
    ) {
        Optional<Actor> caller = caller(http);
        String memberId = caller.map(a -> a.requireMember().toString()).orElse(request.memberId());
        Money target = Money.of(request.targetAmount(), request.getEffectiveCurrency());
        Money monthly = Money.of(request.monthlyAmount(), request.getEffectiveCurrency());
        boolean clientSubscription = request.payPalSubscriptionId() != null && !request.payPalSubscriptionId().isBlank();
        if (clientSubscription && !legacyAllowed) {
            throw new DomainException(SavingsErrors.LEGACY_ID_NOT_ALLOWED, "Remove payPalSubscriptionId; Seed creates it");
        }
        if (clientSubscription || startGoal == null) {
            SavingsGoal created = createGoalUseCase.execute(new CreateGoalUseCase.Command(memberId, request.name(), target, monthly,
                request.targetDate(), request.payPalSubscriptionId()));
            return ResponseEntity.status(HttpStatus.CREATED).body(SavingsGoalResponse.from(created));
        }
        StartGoalUseCase.Started started = startGoal.execute(new StartGoalUseCase.Command(memberId, request.name(), target, monthly,
            request.targetDate(), wallet(request.wallet()), idempotencyKey));
        return ResponseEntity.status(started.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .body(SavingsGoalResponse.from(started.goal()));
    }

    @GetMapping("/goals/{id}")
    public ResponseEntity<SavingsGoalResponse> getGoal(@PathVariable("id") UUID id, HttpServletRequest http) {
        return ResponseEntity.ok(SavingsGoalResponse.from(ownedGoal(id, http)));
    }

    @GetMapping("/members/{memberId}/goals")
    public ResponseEntity<List<SavingsGoalResponse>> getGoalsByMember(@PathVariable("memberId") String memberId,
                                                                      HttpServletRequest http) {
        caller(http).ifPresent(a -> {
            if (!a.requireMember().toString().equalsIgnoreCase(memberId)) {
                throw new DomainException(SavingsErrors.GOAL_NOT_FOUND, "Savings goal not found");
            }
        });
        List<SavingsGoalResponse> responses = repository.findByMemberId(memberId).stream()
            .map(SavingsGoalResponse::from)
            .toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/goals/{id}/pause")
    public ResponseEntity<SavingsGoalResponse> pauseGoal(@PathVariable("id") UUID id, HttpServletRequest http) {
        ownedGoal(id, http);
        SavingsGoal paused = manageGoal != null ? manageGoal.pause(id) : pauseResumeGoalUseCase.pause(id);
        return ResponseEntity.ok(SavingsGoalResponse.from(paused));
    }

    @PostMapping("/goals/{id}/resume")
    public ResponseEntity<SavingsGoalResponse> resumeGoal(@PathVariable("id") UUID id, HttpServletRequest http) {
        ownedGoal(id, http);
        SavingsGoal resumed = manageGoal != null ? manageGoal.resume(id) : pauseResumeGoalUseCase.resume(id);
        return ResponseEntity.ok(SavingsGoalResponse.from(resumed));
    }

    @PostMapping("/goals/{id}/amount")
    public ResponseEntity<SavingsGoalResponse> changeAmount(@PathVariable("id") UUID id, @RequestBody ChangeAmountRequest request,
                                                            HttpServletRequest http) {
        ownedGoal(id, http);
        if (manageGoal == null) {
            throw new IllegalStateException("Changing the amount needs the PayPal wiring");
        }
        Money monthly = Money.of(request.monthlyAmount(), request.getEffectiveCurrency());
        return ResponseEntity.ok(SavingsGoalResponse.from(manageGoal.changeMonthlyAmount(id, monthly)));
    }

    @PostMapping("/goals/{id}/withdraw")
    public ResponseEntity<?> withdraw(
        @PathVariable("id") UUID id,
        @RequestBody WithdrawRequest request,
        @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
        HttpServletRequest http
    ) {
        ownedGoal(id, http);
        Money amount = Money.of(request.amount(), request.getEffectiveCurrency());
        boolean clientPayout = request.payPalPayoutId() != null && !request.payPalPayoutId().isBlank();
        if (clientPayout && !legacyAllowed) {
            throw new DomainException(SavingsErrors.LEGACY_ID_NOT_ALLOWED, "Remove payPalPayoutId; Seed sends the payout");
        }
        if (clientPayout || withdraw == null) {
            ProcessWithdrawalUseCase.Command command = new ProcessWithdrawalUseCase.Command(
                id,
                amount,
                clientPayout ? request.payPalPayoutId() : "manual-wd-" + UUID.randomUUID()
            );
            return ResponseEntity.ok(SavingsGoalResponse.from(processWithdrawalUseCase.execute(command)));
        }
        Withdrawal withdrawal = withdraw.execute(new WithdrawUseCase.Command(id, amount, optionalWallet(request.wallet()), idempotencyKey));
        HttpStatus status = withdrawal.getStatus() == WithdrawalStatus.REQUESTED ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(WithdrawalResponse.from(withdrawal));
    }

    @GetMapping("/goals/{id}/withdrawals")
    public ResponseEntity<List<WithdrawalResponse>> listWithdrawals(@PathVariable("id") UUID id, HttpServletRequest http) {
        ownedGoal(id, http);
        List<WithdrawalResponse> body = withdrawals == null ? List.of()
            : withdrawals.findByGoal(id).stream().map(WithdrawalResponse::from).toList();
        return ResponseEntity.ok(body);
    }

    @PostMapping("/goals/{id}/topup")
    public ResponseEntity<?> topUp(
        @PathVariable("id") UUID id,
        @RequestBody TopUpRequest request,
        @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
        HttpServletRequest http
    ) {
        ownedGoal(id, http);
        Money amount = Money.of(request.amount(), request.getEffectiveCurrency());
        boolean clientCapture = request.payPalCaptureId() != null && !request.payPalCaptureId().isBlank();
        if (clientCapture && !legacyAllowed) {
            throw new DomainException(SavingsErrors.LEGACY_ID_NOT_ALLOWED, "Remove payPalCaptureId; Seed captures the order");
        }
        if (clientCapture || topUp == null) {
            String captureId = clientCapture ? request.payPalCaptureId() : "topup-" + UUID.randomUUID();
            SavingsGoal updated = recordDepositUseCase.execute(new RecordDepositUseCase.Command(id, amount, captureId));
            return ResponseEntity.ok(SavingsGoalResponse.from(updated));
        }
        Order order = topUp.start(id, amount, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(new TopUpOrderResponse(id, order.orderId(), order.status(),
            order.approvalUrl().orElse(null), amount.amount(), amount.currency()));
    }

    @PostMapping("/goals/{id}/topup/{orderId}/capture")
    public ResponseEntity<TopUpCaptureResponse> captureTopUp(@PathVariable("id") UUID id, @PathVariable("orderId") String orderId,
                                                             HttpServletRequest http) {
        ownedGoal(id, http);
        if (topUp == null) {
            throw new IllegalStateException("Capturing a top-up needs the PayPal wiring");
        }
        OrderCapture capture = topUp.capture(id, orderId);
        Money amount = Money.ofCents(capture.amountCents());
        return ResponseEntity.ok(new TopUpCaptureResponse(id, capture.orderId(), capture.status(), amount.amount(), amount.currency()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    /** The signed-in caller, or nobody when this controller was built without sign-in (unit tests). */
    private Optional<Actor> caller(HttpServletRequest http) {
        if (authentication == null) {
            return Optional.empty();
        }
        return Optional.of(authentication.authenticate(http)
            .orElseThrow(() -> new DomainException(CommonErrors.UNAUTHENTICATED, "Sign in to use this route")));
    }

    /** The goal if it exists and belongs to the caller; otherwise a 404 that does not say which. */
    private SavingsGoal ownedGoal(UUID id, HttpServletRequest http) {
        Optional<Actor> caller = caller(http);
        SavingsGoal goal = repository.findById(id)
            .orElseThrow(() -> new DomainException(SavingsErrors.GOAL_NOT_FOUND, "Savings goal not found"));
        caller.ifPresent(a -> {
            if (a.type() != Actor.Type.MEMBER || !goal.getMemberId().equalsIgnoreCase(a.id().toString())) {
                throw new DomainException(SavingsErrors.GOAL_NOT_FOUND, "Savings goal not found");
            }
        });
        return goal;
    }

    private static SavingsWallet wallet(String raw) {
        SavingsWallet parsed = optionalWallet(raw);
        return parsed == null ? SavingsWallet.PAYPAL : parsed;
    }

    private static SavingsWallet optionalWallet(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SavingsWallet.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("wallet must be PAYPAL or VENMO");
        }
    }
}
