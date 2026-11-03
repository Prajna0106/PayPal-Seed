import { isProblem } from "@paypal-seed/api-client";
import type { NextRequest } from "next/server";
import { applicationFilterModel } from "@/features/applications/schema";
import { describeProblem } from "@/features/applications/server/problems";
import { parseGridRequest } from "@/features/applications/server/grid-request";
import { applicationsPage } from "@/features/applications/server/queries";
import { getSession } from "@/server/session";

/**
 * Columns the grid may sort by, mapped to the backend's allow-list. "Age" is the submission time seen the other way
 * round: the oldest application has the biggest age, so ascending age is a descending submission time.
 */
const SORT_COLUMNS = {
  requestedAmountCents: { column: "requestedAmountCents" },
  termMonths: { column: "termMonths" },
  status: { column: "status" },
  savingStreakMonths: { column: "savingStreakMonths" },
  assessmentOutcome: { column: "assessmentOutcome" },
  age: { column: "submittedAt", invert: true },
  slaDueAt: { column: "slaDueAt" },
  assignedOfficer: { column: "assignedOfficer" },
};

/**
 * The applications grid's data source. The browser asks this route for one block of rows; the route asks the backend
 * as the signed-in officer, so identity headers never leave the server. The response carries `lastRow` once the
 * backend has no further page, and the opaque `nextCursor` otherwise.
 */
export async function GET(request: NextRequest) {
  if (!(await getSession())) {
    return Response.json({ title: "Please sign in again." }, { status: 401 });
  }
  const parsed = parseGridRequest(request.nextUrl.searchParams, SORT_COLUMNS, applicationFilterModel);
  if (!parsed.ok) {
    return Response.json({ title: parsed.message }, { status: 400 });
  }
  const { startRow, limit, cursor, sort, filters } = parsed.value;
  try {
    const page = await applicationsPage({
      sort,
      cursor,
      limit,
      status: filters.status ?? [],
      slaBreached: filters.slaBreached ?? false,
    });
    const rows = page.rows ?? [];
    return Response.json(
      {
        rowData: rows,
        ...(page.nextCursor ? { nextCursor: page.nextCursor } : { lastRow: startRow + rows.length }),
        ...(page.total ? { total: page.total } : {}),
        asOf: page.asOf,
      },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    if (isProblem(error)) {
      return Response.json({ title: describeProblem(error), code: error.code }, { status: error.status });
    }
    return Response.json({ title: "We could not reach the Seed backend." }, { status: 502 });
  }
}
