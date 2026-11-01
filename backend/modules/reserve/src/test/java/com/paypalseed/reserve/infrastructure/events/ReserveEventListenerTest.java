package com.paypalseed.reserve.infrastructure.events;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.paypalseed.payments.api.events.PaymentEvents.DonationCaptured;
import com.paypalseed.reserve.api.ReserveFacade;
import com.paypalseed.reserve.application.usecases.CoverService;
import com.paypalseed.reserve.application.usecases.ExposureTracker;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The donation listener passes real gifts to the facade and drops events that cannot be one without failing. */
class ReserveEventListenerTest {

    private final ReserveFacade reserve = mock(ReserveFacade.class);
    private final ReserveEventListener listener = new ReserveEventListener(mock(ExposureTracker.class), mock(CoverService.class), reserve);

    private static DonationCaptured capture(String customId, long gross, long fee) {
        return new DonationCaptured(UUID.randomUUID(), "CAP-1", customId, gross, fee, Instant.parse("2026-11-10T12:00:00Z"));
    }

    @Test
    void aCaptureWithOurCustomIdIsRecordedWithItsAmountsAndTransactionId() {
        String customId = "reserve-donation:" + UUID.randomUUID();
        listener.on(capture(customId, 2_500, 100));
        verify(reserve).recordDonationForCustomId(customId, "CAP-1", 2_500, 100);
    }

    @Test
    void aMalformedIdOfOursIsStillAGiftBecauseTheMoneyArrived() {
        listener.on(capture("reserve-donation:not-a-uuid", 500, 0));
        verify(reserve).recordDonationForCustomId("reserve-donation:not-a-uuid", "CAP-1", 500, 0);
    }

    @Test
    void aForeignOrMissingCustomIdIsIgnored() {
        listener.on(capture("loan:" + UUID.randomUUID() + ":CATCH_UP", 500, 0));
        listener.on(capture(null, 500, 0));
        verify(reserve, never()).recordDonationForCustomId(anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    void amountsThatCannotBeAGiftAreIgnoredInsteadOfFailingForever() {
        String customId = "reserve-donation:" + UUID.randomUUID();
        listener.on(capture(customId, 0, 0));
        listener.on(capture(customId, -5, 0));
        listener.on(capture(customId, 100, 100));
        listener.on(capture(customId, 100, -1));
        verify(reserve, never()).recordDonationForCustomId(anyString(), anyString(), anyLong(), anyLong());
    }
}
