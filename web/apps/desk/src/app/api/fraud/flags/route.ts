import { isProblem } from "@paypal-seed/api-client";
import type { NextRequest } from "next/server";
import { z } from "zod";
import { flagsPage } from "@/features/fraud/server/queries";
import { getSession } from "@/server/session";

const query = z.object({
  status: z.array(z.enum(["OPEN", "REVIEWED_CLEAR", "CONFIRMED"])).default([]),
  before: z.uuid().optional(),
  limit: z.coerce.number().int().min(1).max(200).default(100),
});

/**
 * The flag grid's data source. The browser asks this route; the route asks the backend as the signed-in officer,
 * so identity headers never leave the server. The backend decides who may read flags: a 403 is passed on.
 */
export async function GET(request: NextRequest) {
  if (!(await getSession())) {
    return Response.json({ title: "Please sign in again." }, { status: 401 });
  }
  const params = request.nextUrl.searchParams;
  const parsed = query.safeParse({
    status: params.getAll("status"),
    before: params.get("before") ?? undefined,
    limit: params.get("limit") ?? undefined,
  });
  if (!parsed.success) {
    return Response.json({ title: "Unknown filter." }, { status: 400 });
  }
  const { status, before, limit } = parsed.data;
  try {
    const page = await flagsPage(status, before, limit);
    return Response.json(page, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    if (isProblem(error)) {
      const title = error.status === 403 ? "Your role cannot see fraud flags." : error.title;
      return Response.json({ title, code: error.code }, { status: error.status });
    }
    return Response.json({ title: "We could not reach the Seed backend." }, { status: 502 });
  }
}
