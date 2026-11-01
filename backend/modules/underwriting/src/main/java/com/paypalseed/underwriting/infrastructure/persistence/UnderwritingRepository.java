package com.paypalseed.underwriting.infrastructure.persistence;

import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * Persistence store for member fair-check consents and auditable {@link FairLoanCheckAssessment} records.
 */
@Repository
public class UnderwritingRepository {

    public record ConsentRecord(UUID memberId, boolean consented, Instant updatedAt) {
    }

    private final Map<UUID, ConsentRecord> consents = new ConcurrentHashMap<>();
    private final Map<UUID, FairLoanCheckAssessment> assessments = new ConcurrentHashMap<>();

    public ConsentRecord saveConsent(UUID memberId, boolean consented, Instant now) {
        ConsentRecord rec = new ConsentRecord(memberId, consented, now);
        consents.put(memberId, rec);
        return rec;
    }

    public Optional<ConsentRecord> findConsent(UUID memberId) {
        return Optional.ofNullable(consents.get(memberId));
    }

    public FairLoanCheckAssessment saveAssessment(FairLoanCheckAssessment assessment) {
        assessments.put(assessment.getId(), assessment);
        return assessment;
    }

    public Optional<FairLoanCheckAssessment> findAssessmentById(UUID id) {
        return Optional.ofNullable(assessments.get(id));
    }

    public List<FairLoanCheckAssessment> findAssessmentsByMemberId(UUID memberId) {
        return assessments.values().stream()
            .filter(a -> a.getMemberId().equals(memberId))
            .sorted(Comparator.comparing(FairLoanCheckAssessment::getCreatedAt).reversed())
            .toList();
    }

    public List<FairLoanCheckAssessment> findAllAssessments() {
        return assessments.values().stream()
            .sorted(Comparator.comparing(FairLoanCheckAssessment::getCreatedAt).reversed())
            .toList();
    }
}
