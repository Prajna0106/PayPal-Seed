package com.paypalseed.reserve.domain.model;

import java.math.BigInteger;

/** The fund's coverage of what members owe. */
public final class Coverage {

    private static final BigInteger BASIS_POINTS = BigInteger.valueOf(10_000);

    private Coverage() {
    }

    /**
     * Balance over exposure in basis points, rounded down so the figure never overstates what the fund can do.
     * Null when nothing is owed (a ratio over zero means nothing). Big integers keep huge balances exact.
     */
    public static Long ratioBps(long balanceCents, long exposureCents) {
        if (balanceCents < 0 || exposureCents < 0) {
            throw new IllegalArgumentException("balance and exposure cannot be negative");
        }
        if (exposureCents == 0) {
            return null;
        }
        BigInteger bps = BigInteger.valueOf(balanceCents).multiply(BASIS_POINTS).divide(BigInteger.valueOf(exposureCents));
        return bps.bitLength() < Long.SIZE ? bps.longValue() : Long.MAX_VALUE;
    }
}
