import "server-only";

import { unwrap } from "@paypal-seed/api-client";
import type { Schemas } from "@paypal-seed/api-client/types";
import { staffApi } from "@/server/api";
import type { CoverStatus } from "../model";

export type ReserveSummary = Schemas["ReserveSummary"];
export type CoverRequest = Schemas["CoverRequest"];

export interface CoverPage {
  rows: CoverRequest[];
  nextBefore: string | null;
  /** The server's clock when the page was read (epoch ms), so an SLA breach is judged once, on the server. */
  asOf: number;
}

export async function reserveSummary(): Promise<ReserveSummary> {
  const api = await staffApi();
  return unwrap(api.GET("/api/v1/reserve/summary"));
}

/** One keyset page of cover requests, newest first. */
export async function coverPage(status: CoverStatus[], before: string | undefined, limit: number): Promise<CoverPage> {
  const api = await staffApi();
  const page = await unwrap(
    api.GET("/api/v1/desk/reserve/cover-requests", {
      params: { query: { ...(status.length > 0 ? { status } : {}), ...(before ? { before } : {}), limit } },
    }),
  );
  return { rows: page.rows ?? [], nextBefore: page.nextBefore ?? null, asOf: Date.now() };
}
