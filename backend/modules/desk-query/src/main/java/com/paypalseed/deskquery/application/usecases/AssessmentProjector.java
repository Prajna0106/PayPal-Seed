package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.domain.model.AssessmentOutcome;
import com.paypalseed.deskquery.infrastructure.persistence.ApplicationViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.AssessmentViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.AssessmentViewRepository.Fact;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one snapshot of a fair-check assessment to the decisions queue, and carries its outcome to the linked application.
 * Like application snapshots, these are whole-state and ordered by the instant of the change.
 */
@Service
public class AssessmentProjector {

    /**
     * @param officerDecision PENDING, APPROVED, ADJUSTED or DECLINED; anything else counts as pending
     * @param changedAt       decision time when decided, otherwise creation time
     */
    public record Snapshot(UUID assessmentId, UUID memberRef, UUID applicationId, boolean recommended,
                           long recommendedAmountCents, String officerDecision, boolean disputed, Instant createdAt,
                           Instant changedAt) {
        public Snapshot {
            Objects.requireNonNull(assessmentId, "assessmentId");
            Objects.requireNonNull(memberRef, "memberRef");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(changedAt, "changedAt");
        }
    }

    private final AssessmentViewRepository assessments;
    private final ApplicationViewRepository applications;

    AssessmentProjector(AssessmentViewRepository assessments, ApplicationViewRepository applications) {
        this.assessments = assessments;
        this.applications = applications;
    }

    /** Returns whether the snapshot became the row's state (false for a stale one). */
    @Transactional
    public boolean apply(Snapshot s) {
        applications.lockMember(s.memberRef());
        String status = AssessmentOutcome.status(s.officerDecision());
        String outcome = AssessmentOutcome.of(s.recommended(), s.officerDecision());
        boolean current = assessments.upsert(new Fact(s.assessmentId(), s.memberRef(), s.applicationId(), s.recommended(),
                s.recommendedAmountCents(), status, outcome, s.disputed(), s.createdAt(), s.changedAt()));
        if (!current && s.disputed()) {
            assessments.markDisputed(s.assessmentId());
        }
        if (current && s.applicationId() != null) {
            applications.setAssessmentOutcome(s.applicationId(), outcome);
        }
        return current;
    }
}
