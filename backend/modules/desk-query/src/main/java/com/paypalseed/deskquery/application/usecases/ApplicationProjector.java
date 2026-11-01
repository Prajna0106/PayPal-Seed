package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.infrastructure.persistence.ApplicationViewRepository;
import com.paypalseed.deskquery.infrastructure.persistence.ApplicationViewRepository.Fact;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one snapshot of a loan application to the queue. A snapshot carries the whole current state of the application and
 * the instant of the change, so any snapshot can be applied in any order and any number of times: the newest wins.
 */
@Service
public class ApplicationProjector {

    /**
     * @param assignedOfficerRef pseudonymous reference of the officer who acted last, never a name or an id
     * @param changedAt          when the application reached this state; the guard compares it
     */
    public record Snapshot(UUID applicationId, UUID memberRef, String status, long requestedAmountCents, int termMonths,
                           Instant submittedAt, String assignedOfficerRef, Instant changedAt) {
        public Snapshot {
            Objects.requireNonNull(applicationId, "applicationId");
            Objects.requireNonNull(memberRef, "memberRef");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(submittedAt, "submittedAt");
            Objects.requireNonNull(changedAt, "changedAt");
        }
    }

    private final ApplicationViewRepository applications;
    private final DeskQuerySettings settings;

    ApplicationProjector(ApplicationViewRepository applications, DeskQuerySettings settings) {
        this.applications = applications;
        this.settings = settings;
    }

    /** Returns whether the snapshot became the row's state (false for a stale one). */
    @Transactional
    public boolean apply(Snapshot s) {
        applications.lockMember(s.memberRef());
        return applications.upsert(new Fact(s.applicationId(), s.memberRef(), s.status(), s.requestedAmountCents(), s.termMonths(),
                s.submittedAt(), s.submittedAt().plus(settings.applicationSla()), s.assignedOfficerRef(), s.changedAt()));
    }
}
