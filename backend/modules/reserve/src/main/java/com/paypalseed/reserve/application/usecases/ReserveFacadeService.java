package com.paypalseed.reserve.application.usecases;

import com.paypalseed.reserve.api.ReserveFacade;
import com.paypalseed.reserve.api.dto.ReserveDtos.CoverReturn;
import com.paypalseed.reserve.api.dto.ReserveDtos.DonationIntent;
import com.paypalseed.reserve.api.dto.ReserveDtos.DonationReceipt;
import com.paypalseed.reserve.api.dto.ReserveDtos.ReserveSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReserveFacadeService implements ReserveFacade {

    private final DonationService donations;
    private final SummaryQueries summaries;
    private final CoverReturns coverReturns;

    ReserveFacadeService(DonationService donations, SummaryQueries summaries, CoverReturns coverReturns) {
        this.donations = donations;
        this.summaries = summaries;
        this.coverReturns = coverReturns;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DonationReceipt recordDonation(String captureId, long amountCents, String donorDisplayName, boolean anonymous) {
        return donations.record(captureId, amountCents, 0, donorDisplayName, anonymous);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DonationReceipt recordDonation(String captureId, long amountCents, long feeCents, String donorDisplayName,
                                          boolean anonymous) {
        return donations.record(captureId, amountCents, feeCents, donorDisplayName, anonymous);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DonationReceipt recordDonationForCustomId(String customId, String captureId, long amountCents, long feeCents) {
        return donations.recordForCustomId(customId, captureId, amountCents, feeCents);
    }

    @Override
    public DonationIntent createDonationIntent(String idempotencyKey, boolean anonymous, String displayName) {
        return donations.createIntent(idempotencyKey, anonymous, displayName);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CoverReturn returnUnusedCover(String approvalRef, long amountCents, String reasonCode) {
        return coverReturns.returnUnused(approvalRef, amountCents, reasonCode);
    }

    @Override
    public ReserveSummary summary() {
        return summaries.summary();
    }
}
