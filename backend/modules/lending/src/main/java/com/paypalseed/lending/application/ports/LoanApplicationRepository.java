package com.paypalseed.lending.application.ports;

import com.paypalseed.lending.domain.model.LoanApplication;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for persisting and querying microloan applications (Feature 2).
 */
public interface LoanApplicationRepository {

    LoanApplication save(LoanApplication application);

    Optional<LoanApplication> findById(UUID id);

    /**
     * Loads the application and, in a database, locks its row until the transaction ends, so a payout event and a
     * desk request cannot both act on the old state. A store with no locks just loads it.
     */
    default Optional<LoanApplication> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    List<LoanApplication> findByMemberId(UUID memberId);

    List<LoanApplication> findAll();
}
