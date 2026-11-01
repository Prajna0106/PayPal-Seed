package com.paypalseed.savings.infrastructure.web;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.dto.CoachSuggestionRequest;
import com.paypalseed.savings.api.dto.CoachSuggestionResponse;
import com.paypalseed.savings.api.dto.CreateGoalRequest;
import com.paypalseed.savings.api.dto.SavingsGoalResponse;
import com.paypalseed.savings.api.dto.WithdrawRequest;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.PauseResumeGoalUseCase;
import com.paypalseed.savings.application.usecases.ProcessWithdrawalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.application.usecases.SuggestSavingsPlanUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/savings")
public class SavingsController {

    private final CreateGoalUseCase createGoalUseCase;
    private final RecordDepositUseCase recordDepositUseCase;
    private final ProcessWithdrawalUseCase processWithdrawalUseCase;
    private final PauseResumeGoalUseCase pauseResumeGoalUseCase;
    private final SavingsGoalRepository repository;
    private final SuggestSavingsPlanUseCase suggestSavingsPlanUseCase;

    public SavingsController(
        CreateGoalUseCase createGoalUseCase,
        RecordDepositUseCase recordDepositUseCase,
        ProcessWithdrawalUseCase processWithdrawalUseCase,
        PauseResumeGoalUseCase pauseResumeGoalUseCase,
        SavingsGoalRepository repository
    ) {
        this(
            createGoalUseCase,
            recordDepositUseCase,
            processWithdrawalUseCase,
            pauseResumeGoalUseCase,
            repository,
            new SuggestSavingsPlanUseCase()
        );
    }

    @Autowired
    public SavingsController(
        CreateGoalUseCase createGoalUseCase,
        RecordDepositUseCase recordDepositUseCase,
        ProcessWithdrawalUseCase processWithdrawalUseCase,
        PauseResumeGoalUseCase pauseResumeGoalUseCase,
        SavingsGoalRepository repository,
        @Autowired(required = false) SuggestSavingsPlanUseCase suggestSavingsPlanUseCase
    ) {
        this.createGoalUseCase = createGoalUseCase;
        this.recordDepositUseCase = recordDepositUseCase;
        this.processWithdrawalUseCase = processWithdrawalUseCase;
        this.pauseResumeGoalUseCase = pauseResumeGoalUseCase;
        this.repository = repository;
        this.suggestSavingsPlanUseCase = suggestSavingsPlanUseCase != null
            ? suggestSavingsPlanUseCase
            : new SuggestSavingsPlanUseCase();
    }

    @PostMapping("/coach/suggest")
    public ResponseEntity<CoachSuggestionResponse> suggestPlan(@RequestBody CoachSuggestionRequest request) {
        return ResponseEntity.ok(suggestSavingsPlanUseCase.execute(request));
    }

    @PostMapping("/goals")
    public ResponseEntity<SavingsGoalResponse> createGoal(@RequestBody CreateGoalRequest request) {
        Money target = Money.of(request.targetAmount(), request.getEffectiveCurrency());
        Money monthly = Money.of(request.monthlyAmount(), request.getEffectiveCurrency());

        CreateGoalUseCase.Command command = new CreateGoalUseCase.Command(
            request.memberId(),
            request.name(),
            target,
            monthly,
            request.targetDate(),
            request.payPalSubscriptionId()
        );

        SavingsGoal created = createGoalUseCase.execute(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(SavingsGoalResponse.from(created));
    }

    @GetMapping("/goals/{id}")
    public ResponseEntity<SavingsGoalResponse> getGoal(@PathVariable("id") UUID id) {
        return repository.findById(id)
            .map(goal -> ResponseEntity.ok(SavingsGoalResponse.from(goal)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/members/{memberId}/goals")
    public ResponseEntity<List<SavingsGoalResponse>> getGoalsByMember(@PathVariable("memberId") String memberId) {
        List<SavingsGoalResponse> responses = repository.findByMemberId(memberId).stream()
            .map(SavingsGoalResponse::from)
            .toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/goals/{id}/pause")
    public ResponseEntity<SavingsGoalResponse> pauseGoal(@PathVariable("id") UUID id) {
        SavingsGoal paused = pauseResumeGoalUseCase.pause(id);
        return ResponseEntity.ok(SavingsGoalResponse.from(paused));
    }

    @PostMapping("/goals/{id}/resume")
    public ResponseEntity<SavingsGoalResponse> resumeGoal(@PathVariable("id") UUID id) {
        SavingsGoal resumed = pauseResumeGoalUseCase.resume(id);
        return ResponseEntity.ok(SavingsGoalResponse.from(resumed));
    }

    @PostMapping("/goals/{id}/withdraw")
    public ResponseEntity<SavingsGoalResponse> withdraw(
        @PathVariable("id") UUID id,
        @RequestBody WithdrawRequest request
    ) {
        Money amount = Money.of(request.amount(), request.getEffectiveCurrency());
        ProcessWithdrawalUseCase.Command command = new ProcessWithdrawalUseCase.Command(
            id,
            amount,
            request.payPalPayoutId() != null ? request.payPalPayoutId() : "manual-wd-" + UUID.randomUUID()
        );

        SavingsGoal updated = processWithdrawalUseCase.execute(command);
        return ResponseEntity.ok(SavingsGoalResponse.from(updated));
    }

    @PostMapping("/goals/{id}/topup")
    public ResponseEntity<SavingsGoalResponse> topUp(
        @PathVariable("id") UUID id,
        @RequestBody Map<String, Object> body
    ) {
        BigDecimal amountVal = new BigDecimal(body.get("amount").toString());
        String currency = (String) body.getOrDefault("currency", "USD");
        String captureId = (String) body.getOrDefault("payPalCaptureId", "topup-" + UUID.randomUUID());

        RecordDepositUseCase.Command command = new RecordDepositUseCase.Command(
            id,
            Money.of(amountVal, currency),
            captureId
        );

        SavingsGoal updated = recordDepositUseCase.execute(command);
        return ResponseEntity.ok(SavingsGoalResponse.from(updated));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }
}
