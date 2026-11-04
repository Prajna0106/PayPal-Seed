package com.paypalseed.lending.application.usecases;

import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.application.ports.DisbursementPayouts;
import com.paypalseed.lending.application.ports.DisbursementPayouts.Synced;
import com.paypalseed.lending.domain.model.LendingErrors;
import com.paypalseed.platform.kernel.DomainException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The desk's "Check again" (ADR 0029): asks PayPal how the application's payout stands and applies the answer through the
 * steps the payout event listener uses ({@code onPayoutSucceeded}, {@code onPayoutFailed}, {@code onPayoutReturned}), so the
 * refreshed application comes back in the same response instead of after an asynchronous event. Those steps are idempotent: the
 * event the same read publishes, a webhook that comes later, and a second press of the button change nothing.
 *
 * <p>No transaction here: the read of PayPal commits on its own, and each step opens its own through the application service.
 */
@Service
public class SyncDisbursementPayoutUseCase {

    private final MicroloanApplicationService applications;
    private final DisbursementPayouts payouts;

    public SyncDisbursementPayoutUseCase(MicroloanApplicationService applications, ObjectProvider<DisbursementPayouts> payouts) {
        this.applications = applications;
        this.payouts = payouts.getIfAvailable();
    }

    /** The application after the read; {@code 404 payout-not-found} when none was ever paid out or asked for. */
    public LoanApplicationView execute(UUID applicationId) {
        String reference = applications.currentPayoutReference(applicationId)
            .orElseThrow(() -> new DomainException(LendingErrors.PAYOUT_NOT_FOUND, "This application has no payout"));
        Synced synced = Optional.ofNullable(payouts).flatMap(p -> p.sync(reference))
            .orElseThrow(() -> new DomainException(LendingErrors.PAYOUT_NOT_FOUND, "This application has no payout"));
        switch (synced.outcome()) {
            case SUCCEEDED -> applications.onPayoutSucceeded(reference);
            case FAILED -> applications.onPayoutFailed(reference, synced.reasonCode(), true);
            case UNCLAIMED -> applications.onPayoutFailed(reference, synced.reasonCode() == null ? "UNCLAIMED" : synced.reasonCode(), false);
            case RETURNED -> applications.onPayoutReturned(reference, synced.reasonCode());
            case PENDING -> {
                // nothing to apply yet
            }
        }
        return applications.getApplication(applicationId);
    }
}
