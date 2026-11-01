package com.paypalseed.fraud.domain.rules;

import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.domain.model.Fact;
import com.paypalseed.fraud.domain.model.Signal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The place a payout goes changed in the 72 hours before a loan was paid out. Payout fraud tends to look exactly like
 * this, and so does a member who got a new PayPal account, which is why it is a signal and not a decision.
 */
public final class RecipientChangeRule implements Rule {

    public static final String CODE = "PAYOUT_RECIPIENT_CHANGE";
    static final Duration WINDOW = Duration.ofHours(72);
    private static final int WEIGHT = 40;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Optional<Signal> evaluate(RuleContext context) {
        List<Fact> changes = context.of(FactKind.PAYOUT_RECIPIENT_CHANGED);
        List<Long> ids = new ArrayList<>();
        long closest = Long.MAX_VALUE;
        int disbursements = 0;
        for (Fact disbursement : context.of(FactKind.LOAN_DISBURSED)) {
            Optional<Fact> change = changes.stream()
                    .filter(c -> !c.occurredAt().isAfter(disbursement.occurredAt())
                            && Duration.between(c.occurredAt(), disbursement.occurredAt()).compareTo(WINDOW) <= 0)
                    .reduce((first, second) -> second);
            if (change.isPresent()) {
                disbursements++;
                ids.add(change.get().id());
                ids.add(disbursement.id());
                closest = Math.min(closest, Duration.between(change.get().occurredAt(), disbursement.occurredAt()).toHours());
            }
        }
        if (disbursements == 0) {
            return Optional.empty();
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("disbursements", disbursements);
        evidence.put("hoursBeforeDisbursement", closest);
        evidence.put("windowHours", WINDOW.toHours());
        return Optional.of(new Signal(CODE, WEIGHT,
                "The payout recipient changed " + closest + " hours before a loan was paid out (the window is "
                        + WINDOW.toHours() + " hours)", evidence, ids, context.asOf()));
    }
}
