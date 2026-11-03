import "server-only";

import { unwrap } from "@paypal-seed/api-client";
import type { Schemas } from "@paypal-seed/api-client/types";
import { cache } from "react";
import { staffApi } from "@/server/api";
import type { AgingBucket, LoanStatus, SortColumn } from "../model";

export type PortfolioSummary = Schemas["PortfolioSummary"];
export type LoanPage = Schemas["LoanPage"];

/** Totals, aging and inclusion impact, read in one snapshot by the backend. */
export const portfolioSummary = cache(async (): Promise<PortfolioSummary> => {
  const api = await staffApi();
  return unwrap(api.GET("/api/v1/desk/portfolio/summary"));
});

export interface LoanPageRequest {
  /** One sort column and direction; the backend allows up to three but the grid sorts by one. */
  sort?: `${SortColumn}:${"asc" | "desc"}`;
  status: LoanStatus[];
  agingBucket: AgingBucket[];
  autopay?: boolean;
  hardship?: boolean;
  /** Opaque: signed by the backend for this list, sort and filter. Passed through untouched. */
  cursor?: string;
  limit: number;
}

/** One keyset page of the loan portfolio. */
export async function loanPage(request: LoanPageRequest): Promise<LoanPage> {
  const { cursor, sort, autopay, hardship, status, agingBucket, limit } = request;
  const api = await staffApi();
  return unwrap(
    api.GET("/api/v1/desk/portfolio/loans", {
      params: {
        query: {
          limit,
          ...(sort ? { sort } : {}),
          ...(status.length ? { status } : {}),
          ...(agingBucket.length ? { agingBucket } : {}),
          ...(autopay === undefined ? {} : { autopay }),
          ...(hardship === undefined ? {} : { hardship }),
          ...(cursor ? { cursor } : {}),
        },
      },
    }),
  );
}
