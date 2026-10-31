package com.paypalseed.platform.kernel;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Time-ordered UUIDs (RFC 9562 version 7). Ordering by id follows creation order, which keeps
 * B-tree inserts local and gives a stable tie-break when sorting money events by date.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}
