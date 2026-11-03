"use server";

import { unwrap } from "@paypal-seed/api-client";
import { unstable_rethrow } from "next/navigation";
import { z } from "zod";
import { tooManyAttempts } from "@/features/auth/server/rate-limit";
import { publicApi } from "@/features/record/server/public-api";

export type IntentResult = { ok: true; customId: string } | { ok: false; message: string };

const intentSchema = z.object({
  anonymous: z.boolean(),
  displayName: z.string().trim().max(40).optional(),
});

/**
 * Starts a donation after the donor clicked the PayPal button. The backend remembers the donor's choice about their
 * name and returns the `custom_id` PayPal will carry back in the capture webhook (ADR 0019). Called only on that
 * click, so page views never create rows.
 */
export async function createDonationIntent(input: { anonymous: boolean; displayName?: string }): Promise<IntentResult> {
  const parsed = intentSchema.safeParse(input);
  if (!parsed.success) {
    return { ok: false, message: "Please check the name you entered. It can be up to 40 characters." };
  }
  if (await tooManyAttempts()) {
    return { ok: false, message: "Too many tries in a short time. Please wait a minute and try again." };
  }
  const { anonymous, displayName } = parsed.data;
  const shownName = !anonymous && displayName ? displayName : undefined;
  try {
    const intent = await unwrap(
      publicApi().POST("/api/v1/reserve/donations/intent", {
        params: { header: { "Idempotency-Key": crypto.randomUUID() } },
        body: shownName ? { anonymous: false, displayName: shownName } : { anonymous: true },
      }),
    );
    return { ok: true, customId: intent.customId };
  } catch (error) {
    unstable_rethrow(error);
    console.error("[fund] donation intent failed", error);
    return { ok: false, message: "We could not start your donation. Nothing was charged. Please try again." };
  }
}
