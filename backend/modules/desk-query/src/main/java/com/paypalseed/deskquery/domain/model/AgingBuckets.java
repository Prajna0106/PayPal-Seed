package com.paypalseed.deskquery.domain.model;

import com.paypalseed.engines.schedule.Bucket;
import java.util.Arrays;
import java.util.List;

/**
 * Delinquency buckets as the desk shows them. The ranges belong to the schedule engine ({@link Bucket}); this class only
 * adds what a read model needs: a stable order from healthy to worst, and a defence against a bucket name this module
 * does not know (a newer lending release), which falls back to the days past due instead of failing the event.
 */
public final class AgingBuckets {

    /** Healthy first, worst last. */
    public static final List<String> ORDER = Arrays.stream(Bucket.values()).map(Enum::name).toList();

    private AgingBuckets() {
    }

    public static String of(int daysPastDue) {
        return Bucket.of(Math.max(0, daysPastDue)).name();
    }

    public static boolean isKnown(String bucket) {
        return bucket != null && ORDER.contains(bucket);
    }

    /** The bucket lending reported, or the one the days past due imply when lending reported a name we do not know. */
    public static String resolve(String reported, int daysPastDue) {
        return isKnown(reported) ? reported : of(daysPastDue);
    }

    /** 0 for CURRENT up to the last bucket; used to sort a grid by severity rather than by name. */
    public static int rank(String bucket) {
        int i = ORDER.indexOf(bucket);
        return i < 0 ? 0 : i;
    }
}
