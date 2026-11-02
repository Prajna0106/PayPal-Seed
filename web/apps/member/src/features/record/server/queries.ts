import "server-only";

import { unwrap } from "@paypal-seed/api-client";
import { memberApi } from "@/server/api";
import type { HistoryItem, RecordView, ShareView } from "../model";

export interface RecordPageData {
  record: RecordView;
  history: { items: HistoryItem[]; nextBefore: number | null };
  shares: ShareView[];
  /** Whether the member has agreed to share their record (ADR 0012). The credit-record module does not enforce it. */
  sharingAllowed: boolean;
}

export async function getRecordPage(): Promise<RecordPageData> {
  const api = await memberApi();
  const [record, history, shares, consents] = await Promise.all([
    unwrap(api.GET("/api/v1/members/me/record")),
    unwrap(api.GET("/api/v1/members/me/record/history", { params: { query: { limit: 20 } } })),
    unwrap(api.GET("/api/v1/members/me/record/shares")),
    unwrap(api.GET("/api/v1/members/me/consents")),
  ]);
  return {
    record,
    history: { items: history.items, nextBefore: history.nextBefore ?? null },
    shares,
    sharingAllowed: consents.some((consent) => consent.type === "RECORD_SHARING" && consent.active),
  };
}
