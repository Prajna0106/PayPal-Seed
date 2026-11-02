import "server-only";

import { unwrap } from "@paypal-seed/api-client";
import type { Schemas } from "@paypal-seed/api-client/types";
import { staffApi } from "@/server/api";
import type { FlagStatus } from "../model";

export type FlagView = Schemas["FlagView"];
export type FlagDetail = Schemas["FlagDetail"];
export type FlagEvidence = Schemas["FlagEvidence"];

export interface FlagsPage {
  rows: FlagView[];
  nextBefore: string | null;
  /** The score at which a flag opens, from the page itself. */
  threshold: number;
}

/** One keyset page of flags, newest first. */
export async function flagsPage(status: FlagStatus[], before: string | undefined, limit: number): Promise<FlagsPage> {
  const api = await staffApi();
  const page = await unwrap(
    api.GET("/api/v1/desk/fraud/flags", {
      params: { query: { ...(status.length > 0 ? { status } : {}), ...(before ? { before } : {}), limit } },
    }),
  );
  return { rows: page.rows ?? [], nextBefore: page.nextBefore ?? null, threshold: page.flagThreshold };
}

export async function flagDetail(flagId: string): Promise<FlagDetail> {
  const api = await staffApi();
  return unwrap(api.GET("/api/v1/desk/fraud/flags/{flagId}", { params: { path: { flagId } } }));
}

export async function flagEvidence(flagId: string): Promise<FlagEvidence> {
  const api = await staffApi();
  return unwrap(api.GET("/api/v1/desk/fraud/flags/{flagId}/evidence", { params: { path: { flagId } } }));
}
