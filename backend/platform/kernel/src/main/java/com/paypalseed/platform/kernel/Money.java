package com.paypalseed.platform.kernel;

import java.math.BigDecimal;

/**
 * A USD amount held as integer cents. Every amount in Seed is USD, so the currency is
 * implicit. Integer cents keep arithmetic exact and cheap; conversion to and from PayPal's decimal
 * strings happens only at the edge.
 */
public record Money(long cents) implements Comparable<Money> {

    public static final String CURRENCY = "USD";
    public static final Money ZERO = new Money(0);
    private static final long CENTS_PER_DOLLAR = 100;

    public static Money ofCents(long cents) {
        return new Money(cents);
    }

    public static Money ofDollars(long dollars) {
        return new Money(Math.multiplyExact(dollars, CENTS_PER_DOLLAR));
    }

    /** Parses a PayPal decimal string such as {@code "150.00"}; rejects sub-cent precision. */
    public static Money parse(String decimal) {
        try {
            return new Money(new BigDecimal(decimal).movePointRight(2).longValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Not a valid USD amount: " + decimal, e);
        }
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(cents, other.cents));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(cents, other.cents));
    }

    public boolean isZero() {
        return cents == 0;
    }

    public boolean isPositive() {
        return cents > 0;
    }

    public boolean isWholeDollars() {
        return cents % CENTS_PER_DOLLAR == 0;
    }

    /** Throws unless the amount is a whole number of dollars. */
    public long wholeDollars() {
        if (!isWholeDollars()) {
            throw new IllegalStateException("Not a whole-dollar amount: " + this);
        }
        return cents / CENTS_PER_DOLLAR;
    }

    /** PayPal wire format, for example {@code "150.00"}. */
    public String toDecimalString() {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(cents, other.cents);
    }

    @Override
    public String toString() {
        return "$" + toDecimalString();
    }
}
