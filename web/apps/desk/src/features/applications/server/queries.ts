import "server-only";

import { unwrap, unwrapOrNull } from "@paypal-seed/api-client";
import { unstable_rethrow } from "next/navigation";
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
 * The newest fair check linked to an application. `null` when none exists yet (the contract answers 404
 * `assessment-not-found`) or the read fails: the page then says there is no fair check to show.
 */
export async function fairCheckForApplication(applicationId: string): Promise<FairCheckCard | null> {
  try {
    const api = await staffApi();
    return await unwrapOrNull(
      api.GET("/api/v1/desk/underwriting/assessments/latest", { params: { query: { applicationId } } }),
    );
  } catch (error) {
    unstable_rethrow(error);
    console.error("[applications] latest fair check unavailable", error);
    return null;
  }
}
