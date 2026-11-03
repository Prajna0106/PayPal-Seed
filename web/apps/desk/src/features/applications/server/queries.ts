import "server-only";

import { unwrap, unwrapOrNull } from "@paypal-seed/api-client";
import { staffApi } from "@/server/api";
import type { ApplicationStatus, FairCheckCard, LoanApplication } from "../model";

export interface ApplicationPageQuery {
  sort: string | undefined;
  cursor: string | undefined;
  limit: number;
  status: ApplicationStatus[];
  slaBreached: boolean;
}

/** One keyset block of the loan application queue. `slaBreached` is sent only when on: off means "do not filter". */
export async function applicationsPage(query: ApplicationPageQuery) {
  const api = await staffApi();
  return unwrap(
    api.GET("/api/v1/desk/applications", {
      params: {
        query: {
          ...(query.sort ? { sort: query.sort } : {}),
          ...(query.cursor ? { cursor: query.cursor } : {}),
          limit: query.limit,
          ...(query.status.length > 0 ? { status: query.status } : {}),
          ...(query.slaBreached ? { slaBreached: true } : {}),
        },
      },
    }),
  );
}

/** The contract has one read for an application, shared with members (a member sees only their own; staff see any). */
export async function applicationById(applicationId: string): Promise<LoanApplication | null> {
  const api = await staffApi();
  return unwrapOrNull(api.GET("/api/v1/loans/applications/{applicationId}", { params: { path: { applicationId } } }));
}

/** Staff names for the maker and checker rows. Names are a courtesy: if the directory is down, ids still work. */
export async function staffDirectory(): Promise<Map<string, string>> {
  try {
    const api = await staffApi();
    const staff = await unwrap(api.GET("/api/v1/desk/staff", { params: { query: { includeInactive: true } } }));
    return new Map(staff.map((member) => [member.staffId, member.displayName]));
  } catch {
    return new Map();
  }
}

/**
 * The fair check behind an application. The desk contract lists fair checks but cannot filter by application, so
 * the list is read and the newest check for this application is picked. Null when none exists or the list fails.
 */
export async function fairCheckForApplication(applicationId: string): Promise<FairCheckCard | null> {
  try {
    const api = await staffApi();
    const checks = await unwrap(api.GET("/api/v1/desk/underwriting/assessments"));
    const matching = checks.filter((check) => check.applicationId === applicationId);
    matching.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    return matching[0] ?? null;
  } catch {
    return null;
  }
}
