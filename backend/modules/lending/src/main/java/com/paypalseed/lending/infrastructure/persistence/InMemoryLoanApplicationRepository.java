package com.paypalseed.lending.infrastructure.persistence;

import com.paypalseed.lending.application.ports.LoanApplicationRepository;
import com.paypalseed.lending.domain.model.LoanApplication;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * Thread-safe repository for {@link LoanApplication} instances.
 */
@Repository
public class InMemoryLoanApplicationRepository implements LoanApplicationRepository {

    private final Map<UUID, LoanApplication> store = new ConcurrentHashMap<>();

    @Override
    public LoanApplication save(LoanApplication application) {
        store.put(application.getId(), application);
        return application;
    }

    @Override
    public Optional<LoanApplication> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<LoanApplication> findByMemberId(UUID memberId) {
        return store.values().stream()
            .filter(app -> app.getMemberId().equals(memberId))
            .sorted(Comparator.comparing(LoanApplication::getCreatedAt).reversed())
            .toList();
    }

    @Override
    public List<LoanApplication> findAll() {
        return store.values().stream()
            .sorted(Comparator.comparing(LoanApplication::getCreatedAt).reversed())
            .toList();
    }
}
