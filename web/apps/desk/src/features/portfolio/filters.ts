import { BUCKET_ORDER, STATUS_ORDER, type AgingBucket, type LoanStatus } from "./model";

/**
 * Grid filters live in the URL (`?status=ACTIVE&aging=DPD_8_30&hardship=true`), so a filtered portfolio is a link an
 * officer can share. Anything unknown is dropped rather than guessed at.
 */
export interface LoanSearch {
  status: LoanStatus | "any";
  aging: AgingBucket | "any";
  autopay: "any" | "true" | "false";
  hardship: "any" | "true" | "false";
}

function first(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

const isStatus = (value: string | undefined): value is LoanStatus => STATUS_ORDER.some((s) => s === value);
const isBucket = (value: string | undefined): value is AgingBucket => BUCKET_ORDER.some((b) => b === value);
const flag = (value: string | undefined): "any" | "true" | "false" => (value === "true" || value === "false" ? value : "any");

export function readLoanSearch(params: Record<string, string | string[] | undefined>): LoanSearch {
  const status = first(params.status);
  const aging = first(params.aging);
  return {
    status: isStatus(status) ? status : "any",
    aging: isBucket(aging) ? aging : "any",
    autopay: flag(first(params.autopay)),
    hardship: flag(first(params.hardship)),
  };
}

/** The query string `/api/portfolio/loans` expects. */
export function loanQuery(search: LoanSearch): URLSearchParams {
  const query = new URLSearchParams();
  if (search.status !== "any") {
    query.append("status", search.status);
  }
  if (search.aging !== "any") {
    query.append("agingBucket", search.aging);
  }
  if (search.autopay !== "any") {
    query.set("autopay", search.autopay);
  }
  if (search.hardship !== "any") {
    query.set("hardship", search.hardship);
  }
  return query;
}
