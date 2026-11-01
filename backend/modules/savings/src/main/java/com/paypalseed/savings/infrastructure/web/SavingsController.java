package com.paypalseed.savings.infrastructure.web;

import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.savings.api.dto.CreateGoalRequest;
import com.paypalseed.savings.api.dto.SavingsGoalResponse;
import com.paypalseed.savings.api.dto.WithdrawRequest;
import com.paypalseed.savings.application.ports.SavingsGoalRepository;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.PauseResumeGoalUseCase;
import com.paypalseed.savings.application.usecases.ProcessWithdrawalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.savings.domain.model.SavingsGoal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/savings")
public class SavingsController {

    private final CreateGoalUseCase createGoalUseCase;
    private final RecordDepositUseCase recordDepositUseCase;
    private final ProcessWithdrawalUseCase processWithdrawalUseCase;
    private final PauseResumeGoalUseCase pauseResumeGoalUseCase;
    private final SavingsGoalRepository repository;

    public SavingsController(
        CreateGoalUseCase createGoalUseCase,
        RecordDepositUseCase recordDepositUseCase,
        ProcessWithdrawalUseCase processWithdrawalUseCase,
        PauseResumeGoalUseCase pauseResumeGoalUseCase,
        SavingsGoalRepository repository
    ) {
        this.createGoalUseCase = createGoalUseCase;
        this.recordDepositUseCase = recordDepositUseCase;
        this.processWithdrawalUseCase = processWithdrawalUseCase;
        this.pauseResumeGoalUseCase = pauseResumeGoalUseCase;
        this.repository = repository;
    }

    @PostMapping("/goals")
    public ResponseEntity<SavingsGoalResponse> createGoal(Actor actor, @RequestBody CreateGoalRequest request) {
        String memberId = actor.requireMember().toString();
        Money target = Money.of(request.targetAmount(), request.getEffectiveCurrency());
        Money monthly = Money.of(request.monthlyAmount(), request.getEffectiveCurrency());

        CreateGoalUseCase.Command command = new CreateGoalUseCase.Command(
            memberId,
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
    public ResponseEntity<SavingsGoalResponse> getGoal(Actor actor, @PathVariable("id") UUID id) {
        SavingsGoal goal = actor.type() == Actor.Type.MEMBER ? ownedGoal(actor, id) : staffReadableGoal(actor, id);
        return ResponseEntity.ok(SavingsGoalResponse.from(goal));
    }

    @GetMapping("/members/{memberId}/goals")
    public ResponseEntity<List<SavingsGoalResponse>> getGoalsByMember(
        Actor actor,
        @PathVariable("memberId") String memberId
    ) {
        if (actor.type() == Actor.Type.MEMBER) {
            if (!actor.requireMember().toString().equals(memberId)) {
                throw notFound();
            }
        } else {
            requireStaff(actor);
        }
        List<SavingsGoalResponse> responses = repository.findByMemberId(memberId).stream()
            .map(SavingsGoalResponse::from)
            .toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/goals/{id}/pause")
    public ResponseEntity<SavingsGoalResponse> pauseGoal(Actor actor, @PathVariable("id") UUID id) {
        ownedGoal(actor, id);
        SavingsGoal paused = pauseResumeGoalUseCase.pause(id);
        return ResponseEntity.ok(SavingsGoalResponse.from(paused));
    }

    @PostMapping("/goals/{id}/resume")
    public ResponseEntity<SavingsGoalResponse> resumeGoal(Actor actor, @PathVariable("id") UUID id) {
        ownedGoal(actor, id);
        SavingsGoal resumed = pauseResumeGoalUseCase.resume(id);
        return ResponseEntity.ok(SavingsGoalResponse.from(resumed));
    }

    @PostMapping("/goals/{id}/withdraw")
    public ResponseEntity<SavingsGoalResponse> withdraw(
        Actor actor,
        @PathVariable("id") UUID id,
        @RequestBody WithdrawRequest request
    ) {
        ownedGoal(actor, id);
        Money amount = Money.of(request.amount(), request.getEffectiveCurrency());
        // The payout id is generated here: a client-chosen one could collide with an earlier ledger posting.
        ProcessWithdrawalUseCase.Command command = new ProcessWithdrawalUseCase.Command(
            id,
            amount,
            "manual-wd-" + UUID.randomUUID()
        );

        SavingsGoal updated = processWithdrawalUseCase.execute(command);
        return ResponseEntity.ok(SavingsGoalResponse.from(updated));
    }

    /** SERVICE only: this credits a balance without collecting money, so members must never reach it. */
    @PostMapping("/goals/{id}/topup")
    public ResponseEntity<SavingsGoalResponse> topUp(
        Actor actor,
        @PathVariable("id") UUID id,
        @RequestBody Map<String, Object> body
    ) {
        actor.requireAnyRole(Actor.Role.SERVICE);
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

    /** Another member's goal gets the same 404 as a missing one, so goal ids can't be probed. */
    private SavingsGoal ownedGoal(Actor actor, UUID id) {
        String memberId = actor.requireMember().toString();
        return repository.findById(id)
            .filter(goal -> memberId.equals(goal.getMemberId()))
            .orElseThrow(SavingsController::notFound);
    }

    private SavingsGoal staffReadableGoal(Actor actor, UUID id) {
        requireStaff(actor);
        return repository.findById(id).orElseThrow(SavingsController::notFound);
    }

    private static void requireStaff(Actor actor) {
        actor.requireAnyRole(Actor.Role.OFFICER, Actor.Role.CHECKER, Actor.Role.ADMIN);
    }

    private static DomainException notFound() {
        return new DomainException(CommonErrors.NOT_FOUND, "Savings goal not found");
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
