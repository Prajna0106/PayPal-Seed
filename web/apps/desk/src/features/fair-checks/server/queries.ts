import "server-only";

import { unwrap, unwrapOrNull } from "@paypal-seed/api-client";
import { staffApi } from "@/server/api";
import type { AssessmentStatus, FairCheckCard } from "../model";

export interface AssessmentPageQuery {
  sort: string | undefined;
  cursor: string | undefined;
  limit: number;
  status: AssessmentStatus[];
  disputed: boolean;
}

/** One keyset block of the fair-check decisions queue. `disputed` is sent only when on: off means "do not filter". */
export async function assessmentsPage(query: AssessmentPageQuery) {
  const api = await staffApi();
  return unwrap(
    api.GET("/api/v1/desk/assessments", {
      params: {
        query: {
          ...(query.sort ? { sort: query.sort } : {}),
          ...(query.cursor ? { cursor: query.cursor } : {}),
          limit: query.limit,
          ...(query.status.length > 0 ? { status: query.status } : {}),
          ...(query.disputed ? { disputed: true } : {}),
        },
      },
    }),
  );
}

/** One fair check as an officer reads it (staff see any member's). */
export async function assessmentById(assessmentId: string): Promise<FairCheckCard | null> {
  const api = await staffApi();
  return unwrapOrNull(api.GET("/api/v1/underwriting/assessments/{assessmentId}", { params: { path: { assessmentId } } }));
}
