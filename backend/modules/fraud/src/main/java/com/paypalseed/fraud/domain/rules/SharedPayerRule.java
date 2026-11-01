package com.paypalseed.fraud.domain.rules;

import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.domain.model.Fact;
import com.paypalseed.fraud.domain.model.Signal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The same PayPal payer paying for more than one member. Families share an account, so this is a reason to look, never
 * proof: the officer sees how many members share it and decides. Only a salted hash of the payer id is ever compared.
 */
public final class SharedPayerRule implements Rule {

    public static final String CODE = "SHARED_PAYER";
    private static final int WEIGHT = 40;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Optional<Signal> evaluate(RuleContext context) {
        List<Fact> shared = context.of(FactKind.PAYER_SEEN).stream()
                .filter(f -> f.refHash() != null && context.otherMembersByRef().getOrDefault(f.refHash(), 0) > 0)
                .toList();
        if (shared.isEmpty()) {
            return Optional.empty();
        }
        TreeSet<String> tags = new TreeSet<>();
        int mostOthers = 0;
        for (Fact f : shared) {
            tags.add(f.refHash().substring(0, 8));
            mostOthers = Math.max(mostOthers, context.otherMembersByRef().get(f.refHash()));
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sharedPayers", tags.size());
        evidence.put("otherMembersOnSamePayer", mostOthers);
        evidence.put("payerTags", List.copyOf(tags));
        return Optional.of(new Signal(CODE, WEIGHT,
                "A PayPal payer that pays for this member also pays for " + mostOthers + " other member"
                        + (mostOthers == 1 ? "" : "s"), evidence, shared.stream().map(Fact::id).toList(), context.asOf()));
    }
}
