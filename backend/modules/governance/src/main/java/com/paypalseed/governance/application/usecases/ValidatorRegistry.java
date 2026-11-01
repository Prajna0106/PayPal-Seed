package com.paypalseed.governance.application.usecases;

import com.paypalseed.governance.api.ProposalValidator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Owner modules contribute validators as beans; two validators for one kind fail the application start. */
@Component
public class ValidatorRegistry {

    private final Map<String, ProposalValidator> byKind = new HashMap<>();

    public ValidatorRegistry(List<ProposalValidator> validators) {
        for (ProposalValidator v : validators) {
            ProposalValidator previous = byKind.putIfAbsent(v.kind(), v);
            if (previous != null) {
                throw new IllegalStateException("two validators for proposal kind " + v.kind() + ": "
                        + previous.getClass().getName() + " and " + v.getClass().getName());
            }
        }
    }

    public Optional<ProposalValidator> forKind(String kind) {
        return Optional.ofNullable(byKind.get(kind));
    }
}
