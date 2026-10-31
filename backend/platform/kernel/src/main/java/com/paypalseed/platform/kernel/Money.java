package com.paypalseed.platform.kernel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A USD amount held as integer cents. Every amount in Seed is USD, so the currency is
 * implicit. Integer cents keep arithmetic exact and cheap; conversion to and from PayPal's decimal
 * strings happens only at the edge.
 */
public record Money(long cents) implements Comparable<Money> {

    public static final String CURRENCY = "USD";
    public static final String USD = "USD";
    public static final Money ZERO = new Money(0);
    private static final long CENTS_PER_DOLLAR = 100;

    public static Money ofCents(long cents) {
        return new Money(cents);
    }

    public static Money ofDollars(long dollars) {
        return new Money(Math.multiplyExact(dollars, CENTS_PER_DOLLAR));
    }

    public static Money of(BigDecimal amount, String currency) {
        if (currency != null && !USD.equalsIgnoreCase(currency)) {
            throw new IllegalArgumentException("Only USD is supported: " + currency);
        }
        return of(amount);
    }

    public static Money of(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        return new Money(amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    public static Money of(double amount, String currency) {
        return of(BigDecimal.valueOf(amount), currency);
    }

    public static Money usd(double amount) {
        return of(BigDecimal.valueOf(amount), USD);
    }

    public static Money usd(BigDecimal amount) {
        return of(amount, USD);
    }

    public static Money zero(String currency) {
        return ZERO;
    }

    public static Money zeroUsd() {
        return ZERO;
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

    public Money add(Money other) {
        return plus(other);
    }

    public Money subtract(Money other) {
        return minus(other);
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return compareTo(other) >= 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
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

    public BigDecimal amount() {
        return BigDecimal.valueOf(cents, 2);
    }

    public String currency() {
        return USD;
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
