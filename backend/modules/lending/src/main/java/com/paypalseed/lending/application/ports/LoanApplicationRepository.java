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

    List<LoanApplication> findByMemberId(UUID memberId);

    List<LoanApplication> findAll();
}
