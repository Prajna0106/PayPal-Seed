package com.paypalseed.deskquery.domain.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * The figures the morning briefing is built on, all read from the desk projections. The briefing narrates these and
 * invents none: a loan is healthy when it is not past due, at risk when it sits in any later aging bucket.
 *
 * @param oldestOpenCaseHours whole hours since the oldest open hardship case was opened. Cases carry a business date, not a
 *                            time, so the age is counted in whole business days of 24 hours; 0 when no case is open.
 * @param slaBreaches         queue items (applications and hardship decisions) whose service deadline has passed
 */
public record DeskBriefingFigures(int activeLoans, long outstandingCents, int healthyLoans, int atRiskLoans,
                                  int openHardshipCases, int oldestOpenCaseHours, int slaBreaches) {

    private static final int HOURS_PER_DAY = 24;
    private static final String CURRENT = "CURRENT";

    public DeskBriefingFigures {
        if (activeLoans < 0 || outstandingCents < 0 || healthyLoans < 0 || atRiskLoans < 0 || openHardshipCases < 0
                || oldestOpenCaseHours < 0 || slaBreaches < 0) {
            throw new IllegalArgumentException("briefing figures cannot be negative");
        }
    }

    /** Healthy and at-risk loans partition the active ones, so the three counts always agree. */
    public static DeskBriefingFigures of(List<BucketCount> activeByBucket, int openHardshipCases,
                                         Optional<LocalDate> oldestOpenCaseOn, LocalDate today, long overdueItems) {
        long healthy = 0;
        long atRisk = 0;
        long outstanding = 0;
        for (BucketCount b : activeByBucket) {
            if (CURRENT.equals(b.bucket())) {
                healthy += b.loans();
            } else {
                atRisk += b.loans();
            }
            outstanding += b.outstandingCents();
        }
        long hours = oldestOpenCaseOn.map(d -> Math.max(0, ChronoUnit.DAYS.between(d, today)) * HOURS_PER_DAY).orElse(0L);
        return new DeskBriefingFigures(toInt(healthy + atRisk), outstanding, toInt(healthy), toInt(atRisk), openHardshipCases,
                toInt(hours), toInt(overdueItems));
    }

    /** One aging bucket of active loans. */
    public record BucketCount(String bucket, long loans, long outstandingCents) {
    }

    private static int toInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, value);
    }
}
