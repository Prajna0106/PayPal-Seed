"use server";

import { isProblem, unwrap } from "@paypal-seed/api-client";
import { revalidatePath } from "next/cache";
import { unstable_rethrow } from "next/navigation";
import { z } from "zod";
import { env } from "@/server/env";
import { memberApi } from "@/server/api";
import type { HistoryItem, ShareDetail } from "./model";

const GENERIC = "Something went wrong on our side. Please try again in a moment.";

const PROBLEM_WORDS: Record<string, string> = {
  "record-nothing-to-share": "There is nothing to share yet. Your record starts when you save or repay a loan.",
  "share-limit-reached": "You already have 25 active links. Stop sharing one of them first.",
  "dependency-unavailable": "Sharing is not available right now. Please try again later.",
  "record-sharing-consent-required":
    "Sharing your record needs your OK first. Please allow it below, then make the link. You can stop sharing at any time.",
  "consent-version-stale": "The sharing terms were just updated. Please reload the page and try again.",
  "idempotency-key-reused": "That was already sent with different details. Please reload and try again.",
};

function words(error: unknown): string {
  return isProblem(error) ? (PROBLEM_WORDS[error.code] ?? GENERIC) : GENERIC;
}

export type ConsentState = { status: "idle" } | { status: "granted" } | { status: "error"; message: string };

/** The member's explicit yes to sharing the record. Nothing is shared until they press the button. */
export async function allowSharing(_previous: ConsentState): Promise<ConsentState> {
  const api = await memberApi();
  try {
    await unwrap(api.POST("/api/v1/members/me/consents", { body: { type: "RECORD_SHARING" } }));
  } catch (error) {
    unstable_rethrow(error);
    console.error("[record] consent failed", error);
    return { status: "error", message: words(error) };
  }
  revalidatePath("/app/record");
  return { status: "granted" };
}

export type ShareState =
  | { status: "idle" }
  | { status: "created"; link: string; expiresAt: string; detail: ShareDetail }
  | { status: "error"; message: string; consentNeeded?: true };

const createSchema = z.object({
  expiresInDays: z.coerce.number().int().min(1).max(30),
  detail: z.enum(["LEVEL", "SUMMARY", "FULL"]),
});

/**
 * Creates a share link. The signed token comes back once, so the full link is built here and handed to the page
 * for the member to copy; the list afterwards never has it.
 */
export async function createShare(_previous: ShareState, formData: FormData): Promise<ShareState> {
  const parsed = createSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: "Choose how long the link lasts and what it shows." };
  }
  const api = await memberApi();
  try {
    // The backend refuses a link without a current RECORD_SHARING consent (ADR 0025). Checking first saves a round
    // trip; the problem below still covers a consent given for an older text.
    const consents = await unwrap(api.GET("/api/v1/members/me/consents"));
    if (!consents.some((consent) => consent.type === "RECORD_SHARING" && consent.active)) {
      revalidatePath("/app/record");
      return { status: "error", message: PROBLEM_WORDS["record-sharing-consent-required"] ?? GENERIC, consentNeeded: true };
    }
    const share = await unwrap(
      api.POST("/api/v1/members/me/record/shares", {
        params: { header: { "Idempotency-Key": crypto.randomUUID() } },
        body: parsed.data,
      }),
    );
    revalidatePath("/app/record");
    return {
      status: "created",
      link: `${env.APP_ORIGIN}/verify/${share.token}`,
      expiresAt: share.expiresAt,
      detail: share.detail,
    };
  } catch (error) {
    unstable_rethrow(error);
    console.error("[record] create share failed", error);
    return {
      status: "error",
      message: words(error),
      ...(isProblem(error, "record-sharing-consent-required") ? { consentNeeded: true as const } : {}),
    };
  }
}

export type RevokeState = { status: "idle" } | { status: "revoked" } | { status: "error"; message: string };

const revokeSchema = z.object({ shareId: z.uuid() });

export async function revokeShare(_previous: RevokeState, formData: FormData): Promise<RevokeState> {
  const parsed = revokeSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: "We could not find that link." };
  }
  const api = await memberApi();
  try {
    await unwrap(api.DELETE("/api/v1/members/me/record/shares/{shareId}", { params: { path: { shareId: parsed.data.shareId } } }));
  } catch (error) {
    unstable_rethrow(error);
    if (isProblem(error, "share-not-found")) {
      revalidatePath("/app/record");
      return { status: "error", message: "That link no longer exists." };
    }
    console.error("[record] revoke failed", error);
    return { status: "error", message: words(error) };
  }
  revalidatePath("/app/record");
  return { status: "revoked" };
}

export type HistoryPage = { ok: true; items: HistoryItem[]; nextBefore: number | null } | { ok: false; message: string };

const beforeSchema = z.number().int().min(1);

export async function loadMoreHistory(before: number): Promise<HistoryPage> {
  const parsed = beforeSchema.safeParse(before);
  if (!parsed.success) {
    return { ok: false, message: "We could not load more." };
  }
  const api = await memberApi();
  try {
    const page = await unwrap(
      api.GET("/api/v1/members/me/record/history", { params: { query: { before: parsed.data, limit: 20 } } }),
    );
    return { ok: true, items: page.items, nextBefore: page.nextBefore ?? null };
  } catch (error) {
    unstable_rethrow(error);
    console.error("[record] history failed", error);
    return { ok: false, message: "We could not load more. Please try again." };
  }
}
