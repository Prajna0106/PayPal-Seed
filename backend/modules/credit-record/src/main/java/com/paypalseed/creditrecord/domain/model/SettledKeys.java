package com.paypalseed.creditrecord.domain.model;

import java.util.UUID;

/**
 * The strings the fold keeps in {@link RecordState#settled()} (ADR 0014, ADR 0025). One place defines every shape, so a
 * marker can never be confused with an instalment key. {@code loanId:seq} means the instalment was paid; the rest are
 * remembered facts that let a later fact correct an earlier count, whichever order the events arrived in.
 */
final class SettledKeys {

    /** How a repaid loan is counted. */
    enum LoanClass {
        /** Completed with no miss that is the member's. */
        PLAN,
        /** Completed after at least one miss that is the member's. */
        OFF_PLAN,
        /** The safety fund paid every instalment; the member did not complete it. */
        FUND
    }

    private static final String FUND_PREFIX = "fund:";
    private static final String CLASS_PREFIX = "class:";
    private static final String REPAID_PREFIX = "repaid:";
    private static final String LIFT_PREFIX = "lift:";

    private static final String VOID_PREFIX = "void:";

    private SettledKeys() {
    }

    /** The paid instalment was counted late (otherwise on time). */
    static String late(String instalmentKey) {
        return instalmentKey + "#late";
    }

    /** A miss was recorded for the instalment, whether or not it is still open. */
    static String missed(String instalmentKey) {
        return instalmentKey + "#missed";
    }

    /** The fund paid the whole instalment. */
    static String fund(String instalmentKey) {
        return FUND_PREFIX + instalmentKey;
    }

    static boolean isFund(String key, UUID loanId) {
        return key.startsWith(FUND_PREFIX + loanId + ":");
    }

    static String instalmentOfFund(String fundKey) {
        return fundKey.substring(FUND_PREFIX.length());
    }

    /** True for {@code loanId:seq} and nothing else of that loan. */
    static boolean isInstalment(String key, UUID loanId) {
        String prefix = loanId + ":";
        if (!key.startsWith(prefix) || key.length() == prefix.length()) {
            return false;
        }
        for (int i = prefix.length(); i < key.length(); i++) {
            if (!Character.isDigit(key.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** The loan was repaid; lending counted {@code missedCount} misses on it. */
    static String repaid(UUID loanId, int missedCount) {
        return REPAID_PREFIX + loanId + ":" + missedCount;
    }

    static boolean isRepaid(String key, UUID loanId) {
        return key.startsWith(REPAID_PREFIX + loanId + ":");
    }

    static int missedCountOf(String repaidKey) {
        return Integer.parseInt(repaidKey.substring(repaidKey.lastIndexOf(':') + 1));
    }

    static String classOf(UUID loanId, LoanClass loanClass) {
        return CLASS_PREFIX + loanId + ":" + loanClass.name();
    }

    /** The level rose when this loan was repaid, from the rung with this rank. */
    static String lift(UUID loanId, int fromRank) {
        return LIFT_PREFIX + loanId + ":" + fromRank;
    }

    static boolean isLift(String key) {
        return key.startsWith(LIFT_PREFIX);
    }

    static boolean isLift(String key, UUID loanId) {
        return key.startsWith(LIFT_PREFIX + loanId + ":");
    }

    static int liftedFromRank(String liftKey) {
        return Integer.parseInt(liftKey.substring(liftKey.lastIndexOf(':') + 1));
    }

    /** The loan is void: nothing more that arrives for it counts (ADR 0025 follow-up). */
    static String voided(UUID loanId) {
        return VOID_PREFIX + loanId;
    }

    static boolean isFundCompletion(String key) {
        return key.startsWith(CLASS_PREFIX) && key.endsWith(":" + LoanClass.FUND.name());
    }
}
