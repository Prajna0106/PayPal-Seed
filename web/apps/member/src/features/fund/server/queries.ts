import "server-only";

import type { Schemas } from "@paypal-seed/api-client";
import { publicApi } from "@/features/record/server/public-api";

export type ReserveSummary = Schemas["ReserveSummary"];

/** The public fund figures, or `null` when the backend cannot answer; the page then says so instead of failing. */
export async function getReserveSummary(): Promise<ReserveSummary | null> {
  try {
    const { data, response } = await publicApi().GET("/api/v1/reserve/summary");
    return response.ok && data ? data : null;
  } catch (error) {
    console.error("[fund] summary failed", error);
    return null;
  }
}
