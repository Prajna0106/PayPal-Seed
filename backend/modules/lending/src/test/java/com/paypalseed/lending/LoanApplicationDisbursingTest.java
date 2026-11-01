package com.paypalseed.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.engines.rules.LoanTier;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.lending.domain.model.LoanApplication;
import com.paypalseed.lending.domain.model.LoanApplication.ApplicationStatus;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.lending.domain.model.StaffReferences;
import com.paypalseed.platform.kernel.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The DISBURSING step of the application state machine. */
class LoanApplicationDisbursingTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final UUID ONE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TWO = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static LoanApplication approved() {
        LoanApplication app = LoanApplication.submit(UUID.randomUUID(), "Mixer", 120_000, 8, 15, WalletType.VENMO, true,
            LoanTier.LEVEL_1, 15_000, 15_000, List.of(), NOW);
        app.approve(ONE, NOW);
        app.approve(TWO, NOW);
        return app;
    }

    @Test
    void approvedMovesToDisbursingAndRemembersTheOfficerAndTheFirstReference() {
        LoanApplication app = approved();

        app.startDisbursement(TWO, NOW.plusSeconds(1));

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSING);
        assertThat(app.getLastOfficerId()).isEqualTo(TWO);
        assertThat(app.getDisbursementRef()).isEqualTo("loan-" + app.getId() + "-disb");
        assertThat(app.payoutReference()).isEqualTo(app.getId().toString());
        assertThat(app.getPayoutAttempts()).isEqualTo(1);
    }

    @Test
    void startingAgainWhileDisbursingChangesNothing() {
        LoanApplication app = approved();
        app.startDisbursement(null, NOW);

        app.startDisbursement(null, NOW.plusSeconds(60));

        assertThat(app.getPayoutAttempts()).isEqualTo(1);
        assertThat(app.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void onlyAnApprovedApplicationCanStartDisbursing() {
        LoanApplication submitted = LoanApplication.submit(UUID.randomUUID(), "Mixer", 120_000, 8, 15, WalletType.PAYPAL, true,
            LoanTier.LEVEL_1, 15_000, 15_000, List.of(), NOW);

        assertThatThrownBy(() -> submitted.startDisbursement(null, NOW)).isInstanceOfSatisfying(DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(LendingErrors.APPLICATION_INVALID_STATE));
    }

    @Test
    void aFailedPayoutReturnsToApprovedWithTheReasonAndTheNextAttemptHasANewReference() {
        LoanApplication app = approved();
        app.startDisbursement(ONE, NOW);

        app.payoutFailed("RECEIVER_UNREGISTERED", NOW.plusSeconds(5));

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(app.getPayoutIssue()).isEqualTo("RECEIVER_UNREGISTERED");
        app.startDisbursement(ONE, NOW.plusSeconds(10));
        assertThat(app.getPayoutIssue()).isNull();
        assertThat(app.getPayoutAttempts()).isEqualTo(2);
        assertThat(app.payoutReference()).isEqualTo(app.getId() + "-a2").hasSizeLessThanOrEqualTo(56);
    }

    @Test
    void payoutFailedOnlyAppliesWhileDisbursing() {
        LoanApplication app = approved();

        assertThatThrownBy(() -> app.payoutFailed("X", NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void disbursingIsRegisteredAsDisbursedWithACoolingOffWindow() {
        LoanApplication app = approved();
        app.startDisbursement(ONE, NOW);
        UUID loan = UUID.randomUUID();

        app.markDisbursed(loan, app.expectedDisbursementBatchId(), NOW.plusSeconds(30));

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSED);
        assertThat(app.getLoanId()).isEqualTo(loan);
        assertThat(app.getCoolingOffExpiresAt()).isEqualTo(NOW.plusSeconds(30).plus(LoanApplication.COOLING_OFF_WINDOW));
        assertThat(app.getPayoutIssue()).isNull();
    }

    @Test
    void whileDisbursingTheMemberCannotCancelAndNoOfficerCanReject() {
        LoanApplication app = approved();
        app.startDisbursement(ONE, NOW);

        assertThatThrownBy(() -> app.cancelWithinCoolingOff(NOW)).isInstanceOfSatisfying(DomainException.class,
            e -> assertThat(e.errorCode()).isEqualTo(LendingErrors.APPLICATION_INVALID_STATE));
        assertThatThrownBy(() -> app.reject(ONE, "changed my mind", NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> app.approve(ONE, NOW)).isInstanceOf(DomainException.class);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSING);
    }

    @Test
    void theStaffReferenceHasGovernancesFormAndHidesTheId() {
        String ref = StaffReferences.of(ONE);

        assertThat(ref).startsWith("staff:").hasSize(22).doesNotContain(ONE.toString());
        assertThat(ref).isEqualTo(StaffReferences.of(ONE)).isNotEqualTo(StaffReferences.of(TWO));
        // the value governance's StaffRefs gives this id: sha256("governance-staff:" + id), first 16 hex digits
        assertThat(ref).isEqualTo("staff:9f5d668017b8d673");
    }
}
