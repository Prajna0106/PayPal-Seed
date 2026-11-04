package com.paypalseed.lending.domain.model;

import com.paypalseed.engines.schedule.Bucket;
import com.paypalseed.engines.schedule.RepaymentPolicy;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** A loan as stored. Derived fields (outstanding, bucket, dpd) are caches of the last recalculation. */
public record LoanRow(
        UUID id,
        UUID memberId,
        long principalCents,
        int instalmentCount,
        int dueDay,
        LocalDate disbursedOn,
        String policyVersion,
        LoanStatus status,
        CollectionMode collectionMode,
        int currentVersion,
        long outstandingCents,
        long overpaidCents,
        int dpd,
        Bucket bucket,
        LocalDate bucketSince,
        LocalDate lastClosedOn,
        LocalDate closedOn,
        long rowVersion) {

    /** The shard count is fixed in the schema ({@code loan.shard BETWEEN 0 AND 63}); workers group shards. */
    private static final int VIRTUAL_SHARDS = 64;

    public enum LoanStatus {
        ACTIVE,
        REPAID,
        COVERED,
        CANCELLED,
        /**
         * The payout that funded the loan was taken back by PayPal after the loan was registered (ADR 0024). The
         * member never kept the money: nothing is owed, nothing runs, and the loan counts for no one.
         */
        DISBURSEMENT_RETURNED
    }

    public enum CollectionMode {
        AUTOPAY,
        MANUAL
    }

    public RepaymentPolicy policy() {
        return RepaymentPolicy.forVersion(policyVersion);
    }

    public boolean isActive() {
        return status == LoanStatus.ACTIVE;
    }

    /** True for a loan whose disbursement came back: it is closed, owes nothing, and only holds money to return. */
    public boolean isVoided() {
        return status == LoanStatus.DISBURSEMENT_RETURNED;
    }

    /** True for a loan the member cancelled in the cooling-off window: closed, nothing owed, nothing planned. */
    public boolean isCancelled() {
        return status == LoanStatus.CANCELLED;
    }

    /**
     * True for a loan that never stood (voided or cancelled in the cooling-off window). Whatever reaches it later, a
     * payment or a refund, is money held for the member and given back, never a repayment (ADR 0024 closing pass).
     */
    public boolean isUnwound() {
        return isVoided() || isCancelled();
    }

    /** One of 64 virtual shards; close of business maps them onto however many workers run. */
    public static int shardOf(UUID loanId) {
        return Math.floorMod(loanId.hashCode(), VIRTUAL_SHARDS);
    }

    /**
     * The virtual shards owned by one worker group: the group number, then every {@code shardCount} after it.
     * Listing them, rather than testing {@code shard % shardCount}, lets the database use the shard index.
     */
    public static List<Integer> shardsOfGroup(int group, int shardCount) {
        return IntStream.iterate(group, s -> s < VIRTUAL_SHARDS, s -> s + shardCount).boxed().toList();
    }
}
