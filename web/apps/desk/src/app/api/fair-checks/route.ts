import { isProblem } from "@paypal-seed/api-client";
import type { NextRequest } from "next/server";
import { fairCheckFilterModel } from "@/features/fair-checks/schema";
import { assessmentsPage } from "@/features/fair-checks/server/queries";
import { parseGridRequest } from "@/features/applications/server/grid-request";
import { describeProblem } from "@/features/applications/server/problems";
import { getSession } from "@/server/session";

/**
 * Columns the grid may sort by, mapped to the backend's allow-list. "Age" is the creation time seen the other way
 * round, so ascending age (youngest first) is a descending creation time, the backend's own default.
 */
const SORT_COLUMNS = {
  recommendedAmountCents: { column: "recommendedAmountCents" },
  status: { column: "status" },
  age: { column: "createdAt", invert: true },
  createdAt: { column: "createdAt" },
};

/** The fair-check queue's data source: one block of rows per request, sorted and filtered by the backend. */
export async function GET(request: NextRequest) {
  if (!(await getSession())) {
    return Response.json({ title: "Please sign in again." }, { status: 401 });
  }
  const parsed = parseGridRequest(request.nextUrl.searchParams, SORT_COLUMNS, fairCheckFilterModel);
  if (!parsed.ok) {
    return Response.json({ title: parsed.message }, { status: 400 });
  }
  const { startRow, limit, cursor, sort, filters } = parsed.value;
  try {
    const page = await assessmentsPage({
      sort,
      cursor,
      limit,
      status: filters.status ?? [],
      disputed: filters.disputed ?? false,
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
