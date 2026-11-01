package com.paypalseed.reserve.api;

import com.paypalseed.reserve.api.dto.ReserveDtos.DonationIntent;
import com.paypalseed.reserve.api.dto.ReserveDtos.DonationReceipt;
import com.paypalseed.reserve.api.dto.ReserveDtos.ReserveSummary;

/**
 * The community safety fund (ADR 0019). Reserve pays loans, never people: money leaves the fund only as a cover
 * request that governance approved with a maker and a different checker.
 */
public interface ReserveFacade {

    /**
     * Records a PayPal capture as a gift and posts it to the ledger, in the caller's transaction (MANDATORY). Idempotent
     * on {@code captureId}: the same capture with the same amount returns the first receipt; another amount is
     * {@code idempotency-key-reused}. The donor is anonymous unless {@code anonymous} is false and a usable name is given.
     */
    DonationReceipt recordDonation(String captureId, long amountCents, String donorDisplayName, boolean anonymous);

    /** As above, with the PayPal fee the capture cost; the fund receives the gross amount, the fee is a program cost. */
    DonationReceipt recordDonation(String captureId, long amountCents, long feeCents, String donorDisplayName,
                                   boolean anonymous);

    /**
     * Records a capture whose PayPal {@code custom_id} is {@code customId}: the donor's choices come from the intent the
     * id names. A custom id that is not a known intent records an anonymous gift.
     */
    DonationReceipt recordDonationForCustomId(String customId, String captureId, long amountCents, long feeCents);

    /** Remembers the donor's choices and returns the custom id for the PayPal order. Idempotent on the key. */
    DonationIntent createDonationIntent(String idempotencyKey, boolean anonymous, String displayName);

    ReserveSummary summary();
}
