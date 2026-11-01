package com.paypalseed.reserve.application.usecases;

import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.reserve.api.dto.ReserveDtos.ReserveSummary;
import com.paypalseed.reserve.domain.model.Coverage;
import com.paypalseed.reserve.domain.model.FundState;
import com.paypalseed.reserve.infrastructure.persistence.DonationRepository;
import com.paypalseed.reserve.infrastructure.persistence.ExposureRepository;
import com.paypalseed.reserve.infrastructure.persistence.FundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The public transparency figures. Nothing here identifies a donor who did not opt in, or a member. */
@Service
public class SummaryQueries {

    private static final int SUPPORTERS_SHOWN = 10;

    private final FundRepository funds;
    private final ExposureRepository exposure;
    private final DonationRepository donations;
    private final BusinessClock clock;

    public SummaryQueries(FundRepository funds, ExposureRepository exposure, DonationRepository donations, BusinessClock clock) {
        this.funds = funds;
        this.exposure = exposure;
        this.donations = donations;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReserveSummary summary() {
        FundState fund = funds.read();
        long owed = exposure.total();
        return new ReserveSummary(fund.balanceCents(), fund.totalDonatedCents(), fund.donationCount(), fund.totalPaidOutCents(),
                fund.membersCovered(), owed, Coverage.ratioBps(fund.balanceCents(), owed),
                donations.recentSupporters(SUPPORTERS_SHOWN), clock.today());
    }
}
