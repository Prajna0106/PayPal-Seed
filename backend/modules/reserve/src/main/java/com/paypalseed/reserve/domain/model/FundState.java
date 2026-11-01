package com.paypalseed.reserve.domain.model;

import com.paypalseed.platform.kernel.DomainException;
import java.util.Map;

/**
 * The fund's counters and the only place they change. Every transition is exact (cents in, cents out, no
 * rounding), refuses to go negative and refuses to overflow, so the stored row can never describe an impossible fund.
 */
public record FundState(long balanceCents, long totalDonatedCents, long totalPaidOutCents, int donationCount,
                        int membersCovered) {

    public static final FundState EMPTY = new FundState(0, 0, 0, 0, 0);

    public FundState {
        if (balanceCents < 0 || totalDonatedCents < 0 || totalPaidOutCents < 0 || donationCount < 0 || membersCovered < 0) {
            throw new IllegalArgumentException("fund counters cannot be negative");
        }
        if (balanceCents != totalDonatedCents - totalPaidOutCents) {
            throw new IllegalArgumentException("balance must equal donated minus paid out");
        }
    }

    /** A gift of {@code cents} (the gross amount). */
    public FundState donate(long cents) {
        if (cents <= 0) {
            throw new IllegalArgumentException("a donation must be positive");
        }
        try {
            return new FundState(Math.addExact(balanceCents, cents), Math.addExact(totalDonatedCents, cents),
                    totalPaidOutCents, Math.incrementExact(donationCount), membersCovered);
        } catch (ArithmeticException e) {
            throw new DomainException(ReserveErrors.FUND_LIMIT_REACHED, "The fund counters would overflow");
        }
    }

    public boolean canCover(long cents) {
        return cents > 0 && cents <= balanceCents;
    }

    /** A cover of {@code cents}; {@code newMember} is true the first time the fund helps this member. */
    public FundState payOut(long cents, boolean newMember) {
        if (!canCover(cents)) {
            throw new DomainException(ReserveErrors.RESERVE_INSUFFICIENT_FUNDS,
                    "The reserve holds " + balanceCents + " cents; the cover needs " + cents,
                    Map.of("balanceCents", balanceCents, "requestedCents", cents));
        }
        try {
            return new FundState(balanceCents - cents, totalDonatedCents, Math.addExact(totalPaidOutCents, cents),
                    donationCount, newMember ? Math.incrementExact(membersCovered) : membersCovered);
        } catch (ArithmeticException e) {
            throw new DomainException(ReserveErrors.FUND_LIMIT_REACHED, "The fund counters would overflow");
        }
    }
}
