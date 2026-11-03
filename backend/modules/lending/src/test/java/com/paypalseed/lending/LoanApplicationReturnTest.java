package com.paypalseed.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.engines.rules.LoanTier;
import com.paypalseed.lending.domain.model.LoanApplication;
import com.paypalseed.lending.domain.model.LoanApplication.ApplicationStatus;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.platform.kernel.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A payout PayPal takes back after the loan was registered (ADR 0024), as the application sees it. */
class LoanApplicationReturnTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final UUID ONE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TWO = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static LoanApplication disbursed() {
        LoanApplication app = LoanApplication.submit(UUID.randomUUID(), "Mixer", 120_000, 8, 15, WalletType.PAYPAL, true,
            LoanTier.LEVEL_1, 15_000, 15_000, List.of(), NOW);
        app.approve(ONE, NOW);
        app.approve(TWO, NOW);
        app.startDisbursement(TWO, NOW.plusSeconds(1));
        app.markDisbursed(UUID.randomUUID(), app.expectedDisbursementBatchId(), NOW.plusSeconds(30));
        return app;
    }

    @Test
    void aReturnedLoanDetachesAndTheApplicationIsApprovedAgainWithTheReason() {
        LoanApplication app = disbursed();

        app.loanReturned(NOW.plusSeconds(3600));

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(app.getPayoutIssue()).isEqualTo(LoanApplication.RETURNED_AFTER_DISBURSEMENT);
        assertThat(app.getLoanId()).isNull();
        assertThat(app.getDisbursementRef()).isNull();
        assertThat(app.getDisbursedAt()).isNull();
        assertThat(app.getCoolingOffExpiresAt()).as("a loan that never was has no cooling-off").isNull();
        assertThat(app.getDisbursementVoids()).isEqualTo(1);
        assertThat(app.getUpdatedAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void theNextDisbursementUsesANewPayoutReferenceAndANewLoanReference() {
        LoanApplication app = disbursed();
        String firstLoanRef = app.getDisbursementRef();
        String firstPayout = app.payoutReference();
        app.loanReturned(NOW.plusSeconds(60));

        app.startDisbursement(TWO, NOW.plusSeconds(120));

        assertThat(app.payoutReference()).isNotEqualTo(firstPayout).isEqualTo(app.getId() + "-a2");
        assertThat(app.getDisbursementRef()).isNotEqualTo(firstLoanRef).isEqualTo("loan-" + app.getId() + "-disb-v1");
        assertThat(app.getPayoutIssue()).as("sending again clears the reason").isNull();
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DISBURSING);
    }

    @Test
    void anApplicationThatWasNeverVoidedKeepsTheOriginalLoanReferenceEvenAfterAFailedAttempt() {
        LoanApplication app = LoanApplication.submit(UUID.randomUUID(), "Mixer", 120_000, 8, 15, WalletType.PAYPAL, true,
            LoanTier.LEVEL_1, 15_000, 15_000, List.of(), NOW);
        app.approve(ONE, NOW);
        app.approve(TWO, NOW);
        app.startDisbursement(TWO, NOW);
        app.payoutFailed("RECEIVER_UNREGISTERED", NOW);
        app.startDisbursement(TWO, NOW.plusSeconds(1));

        assertThat(app.expectedDisbursementBatchId()).isEqualTo("loan-" + app.getId() + "-disb");
        assertThat(app.payoutReference()).isEqualTo(app.getId() + "-a2");
    }

    @Test
    void onlyADisbursedApplicationCanHaveItsLoanVoided() {
        LoanApplication app = disbursed();
        app.loanReturned(NOW.plusSeconds(60));

        assertThatThrownBy(() -> app.loanReturned(NOW.plusSeconds(61))).isInstanceOf(DomainException.class);
        assertThat(app.getDisbursementVoids()).isEqualTo(1);
    }

    @Test
    void anApprovedApplicationWhosePayoutBrokeCanBeRejectedButAPlainApprovedOneCannot() {
        LoanApplication plain = LoanApplication.submit(UUID.randomUUID(), "Mixer", 120_000, 8, 15, WalletType.PAYPAL, true,
            LoanTier.LEVEL_1, 15_000, 15_000, List.of(), NOW);
        plain.approve(ONE, NOW);
        plain.approve(TWO, NOW);
        assertThatThrownBy(() -> plain.reject(ONE, "no", NOW)).isInstanceOf(DomainException.class);

        LoanApplication returned = disbursed();
        returned.loanReturned(NOW.plusSeconds(60));
        returned.reject(ONE, "Recipient could not be fixed", NOW.plusSeconds(120));

        assertThat(returned.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
    }

    @Test
    void aStoredVoidCountComesBackAsTheSameReference() {
        LoanApplication app = disbursed();
        app.loanReturned(NOW.plusSeconds(60));
        app.startDisbursement(TWO, NOW.plusSeconds(61));
        app.markDisbursed(UUID.randomUUID(), app.expectedDisbursementBatchId(), NOW.plusSeconds(62));
        app.loanReturned(NOW.plusSeconds(120));

        LoanApplication restored = LoanApplication.submit(app.getMemberId(), "Mixer", 120_000, 8, 15, WalletType.PAYPAL, true,
            LoanTier.LEVEL_1, 15_000, 15_000, List.of(), NOW);
        restored.restoreDisbursementVoids(app.getDisbursementVoids());

        assertThat(restored.getDisbursementVoids()).isEqualTo(2);
        assertThat(restored.expectedDisbursementBatchId()).endsWith("-disb-v2");
    }
}
