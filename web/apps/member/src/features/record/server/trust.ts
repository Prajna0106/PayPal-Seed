import "server-only";

import { unwrap } from "@paypal-seed/api-client";
import type { Locale } from "@paypal-seed/i18n";
import { unstable_rethrow } from "next/navigation";
import { memberApi } from "@/server/api";

export interface TrustView {
  /** The explanation in the member's language. */
  explanation: string;
  /** Third-party certificate wording. The backend sends one English text. */
  attestation: string;
  advice: string;
  /** True when the Trust Agent wrote the text above; false when it is the fixed template. */
  aiWritten: boolean;
  /** Every figure is the backend's, shown as sent. */
  figures: {
    onTimeRepayments: number;
    streakMonths: number;
    totalSavedCents: number;
    totalRepaidCents: number;
    currentLimitCents: number;
    /** Null at the top level: there is no next step. */
    nextLimitCents: number | null;
  };
  verificationHash: string;
  issuedOn: string;
}

/** The trust record in words. The page stays whole if this part is unavailable, so a failure returns null. */
export async function getTrustRecord(locale: Locale): Promise<TrustView | null> {
  try {
    const api = await memberApi();
    const trust = await unwrap(api.GET("/api/v1/members/me/trust-record"));
    return {
      explanation: locale === "es" ? trust.summaryEs : trust.summaryEn,
      attestation: trust.certificateAttestation,
      advice: trust.milestoneAdvice,
      aiWritten: trust.modelGenerated,
      figures: {
        onTimeRepayments: trust.onTimeRepaymentsCount,
        streakMonths: trust.streakMonths,
        totalSavedCents: trust.totalSavedCents,
        totalRepaidCents: trust.totalRepaidCents,
        currentLimitCents: trust.currentLimitCents,
        nextLimitCents: trust.nextLimitCents,
      },
      verificationHash: trust.verificationHash,
      issuedOn: trust.issuedAt,
    };
  } catch (error) {
    unstable_rethrow(error);
    return null;
  }
}
