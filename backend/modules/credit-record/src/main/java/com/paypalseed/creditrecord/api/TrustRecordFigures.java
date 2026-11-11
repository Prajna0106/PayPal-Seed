package com.paypalseed.creditrecord.api;

import java.util.UUID;

/**
 * The money and saving figures behind a member's trust record that the record itself does not keep: it holds counts and
 * dates only (ADR 0014) and imports no savings or lending class, so the wiring module implements this from those modules'
 * facades. It lives in {@code api} so that module may implement it.
 *
 * <p>Implementations answer for the one member they are asked about and nothing else.
 */
public interface TrustRecordFigures {

    /**
     * @param savingStreakMonths consecutive months of on-time deposits, as savings and lending already count them
     * @param totalSavedCents    what the member holds in their goals now
     * @param totalRepaidCents   what the member has paid on loans that stand, not counting what the safety fund paid
     */
    record MemberFigures(int savingStreakMonths, long totalSavedCents, long totalRepaidCents) {

        public static final MemberFigures NONE = new MemberFigures(0, 0L, 0L);

        public MemberFigures {
            if (savingStreakMonths < 0 || totalSavedCents < 0 || totalRepaidCents < 0) {
                throw new IllegalArgumentException("figures cannot be negative");
            }
        }
    }

    /** A member with no goals and no loans gets {@link MemberFigures#NONE}, not an error. */
    MemberFigures of(UUID memberId);
}
