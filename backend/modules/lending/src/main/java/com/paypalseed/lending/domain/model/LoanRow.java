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
        CANCELLED
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
