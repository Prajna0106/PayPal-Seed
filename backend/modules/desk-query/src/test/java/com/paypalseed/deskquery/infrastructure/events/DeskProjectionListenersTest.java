package com.paypalseed.deskquery.infrastructure.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.paypalseed.deskquery.application.usecases.ApplicationProjector;
import com.paypalseed.deskquery.application.usecases.ApprovalProjector;
import com.paypalseed.deskquery.application.usecases.AssessmentProjector;
import com.paypalseed.deskquery.application.usecases.HardshipProjector;
import com.paypalseed.deskquery.application.usecases.LoanProjector;
import com.paypalseed.deskquery.application.usecases.SavingsProjector;
import com.paypalseed.lending.api.events.LoanApplicationChanged;
import com.paypalseed.underwriting.api.events.AssessmentChanged;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The two snapshot events reach the projectors field for field, with no field invented, dropped or renamed. */
class DeskProjectionListenersTest {

    private final ApplicationProjector applications = mock(ApplicationProjector.class);
    private final AssessmentProjector assessments = mock(AssessmentProjector.class);
    private final DeskProjectionListeners listeners = new DeskProjectionListeners(mock(LoanProjector.class),
            mock(HardshipProjector.class), mock(SavingsProjector.class), mock(ApprovalProjector.class), applications, assessments);

    @Test
    void anApplicationSnapshotIsHandedOnWhateverItsStatus() {
        Instant submitted = Instant.parse("2026-11-10T09:00:00Z");
        Instant changed = submitted.plusSeconds(90);
        UUID application = UUID.randomUUID();
        UUID member = UUID.randomUUID();

        // DISBURSING is the status lending uses while PayPal has not yet confirmed the loan payout.
        listeners.on(new LoanApplicationChanged(UUID.randomUUID(), application, member, "DISBURSING", 120_000, 8, submitted, "staff-ab12cd34",
                changed));

        ArgumentCaptor<ApplicationProjector.Snapshot> snapshot = ArgumentCaptor.forClass(ApplicationProjector.Snapshot.class);
        verify(applications).apply(snapshot.capture());
        assertThat(snapshot.getValue()).isEqualTo(new ApplicationProjector.Snapshot(application, member, "DISBURSING", 120_000, 8, submitted,
                "staff-ab12cd34", changed));
    }

    @Test
    void anApplicationNobodyHasActedOnHasNoOfficer() {
        Instant submitted = Instant.parse("2026-11-10T09:00:00Z");
        listeners.on(new LoanApplicationChanged(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SUBMITTED", 50_000, 6, submitted,
                null, submitted));

        ArgumentCaptor<ApplicationProjector.Snapshot> snapshot = ArgumentCaptor.forClass(ApplicationProjector.Snapshot.class);
        verify(applications).apply(snapshot.capture());
        assertThat(snapshot.getValue().assignedOfficerRef()).isNull();
        assertThat(snapshot.getValue().status()).isEqualTo("SUBMITTED");
    }

    @Test
    void anAssessmentSnapshotKeepsItsDecisionDisputeAndLink() {
        Instant created = Instant.parse("2026-11-10T09:00:00Z");
        Instant decided = created.plusSeconds(3_600);
        UUID assessment = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID application = UUID.randomUUID();

        listeners.on(new AssessmentChanged(UUID.randomUUID(), assessment, member, application, true, 90_000, "ADJUSTED", true, created,
                decided));

        ArgumentCaptor<AssessmentProjector.Snapshot> snapshot = ArgumentCaptor.forClass(AssessmentProjector.Snapshot.class);
        verify(assessments).apply(snapshot.capture());
        assertThat(snapshot.getValue()).isEqualTo(new AssessmentProjector.Snapshot(assessment, member, application, true, 90_000, "ADJUSTED",
                true, created, decided));
    }

    @Test
    void anAssessmentOfNoApplicationStaysUnlinked() {
        Instant created = Instant.parse("2026-11-10T09:00:00Z");
        listeners.on(new AssessmentChanged(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, false, 0, "PENDING", false, created,
                created));

        ArgumentCaptor<AssessmentProjector.Snapshot> snapshot = ArgumentCaptor.forClass(AssessmentProjector.Snapshot.class);
        verify(assessments).apply(snapshot.capture());
        assertThat(snapshot.getValue().applicationId()).isNull();
    }
}
