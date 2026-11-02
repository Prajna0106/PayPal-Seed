package com.paypalseed.identity.application.usecases;

import com.paypalseed.identity.api.dto.ConsentType;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The current version of each consent text. A member can only agree to the version they were shown, so a grant for
 * any other version is refused. Raising a version asks members to agree again; older lines stay in the ledger.
 */
@Component
public class ConsentCatalog {

    private final Map<ConsentType, String> current = new EnumMap<>(ConsentType.class);

    ConsentCatalog(@Value("${seed.identity.consent.terms-version:2026-11}") String terms,
                   @Value("${seed.identity.consent.privacy-version:2026-11}") String privacy,
                   @Value("${seed.identity.consent.income-read-version:2026-11}") String incomeRead,
                   @Value("${seed.identity.consent.record-sharing-version:2026-11}") String recordSharing) {
        current.put(ConsentType.TERMS, terms);
        current.put(ConsentType.PRIVACY, privacy);
        current.put(ConsentType.INCOME_READ, incomeRead);
        current.put(ConsentType.RECORD_SHARING, recordSharing);
    }

    public String currentVersion(ConsentType type) {
        return current.get(type);
    }
}
