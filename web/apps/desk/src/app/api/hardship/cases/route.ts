import { isProblem } from "@paypal-seed/api-client";
import type { NextRequest } from "next/server";
import { z } from "zod";
import { queuePage } from "@/features/hardship/server/queries";
import { getSession } from "@/server/session";

const statuses = [
  "DETECTED",
  "OUTREACH_SENT",
  "OFFER_VIEWED",
  "OPTION_CHOSEN",
  "PENDING_APPROVAL",
  "APPROVED",
  "APPROVED_EXECUTION_FAILED",
  "APPLIED",
  "DECLINED_BY_MEMBER",
  "REJECTED_BY_OFFICER",
  "EXPIRED",
  "CANCELLED_PAID",
  "SUPERSEDED",
] as const;

const query = z.object({
  status: z.array(z.enum(statuses)).default([]),
  level: z.array(z.enum(["NONE", "WATCH", "LIKELY_MISS", "IMMINENT"])).default([]),
  origin: z.array(z.enum(["PROACTIVE_SCAN", "MEMBER_HELP", "STAFF"])).default([]),
  before: z.uuid().optional(),
  limit: z.coerce.number().int().min(1).max(200).default(100),
});

/**
 * The queue grid's data source. The browser asks this route; the route asks the backend as the signed-in officer,
 * so identity headers never leave the server.
 */
export async function GET(request: NextRequest) {
  if (!(await getSession())) {
    return Response.json({ title: "Please sign in again." }, { status: 401 });
  }
  const params = request.nextUrl.searchParams;
  const parsed = query.safeParse({
    status: params.getAll("status"),
    level: params.getAll("level"),
    origin: params.getAll("origin"),
    before: params.get("before") ?? undefined,
    limit: params.get("limit") ?? undefined,
  });
  if (!parsed.success) {
    return Response.json({ title: "Unknown filter." }, { status: 400 });
  }
  const { before, limit, ...filters } = parsed.data;
  try {
    const page = await queuePage(filters, before, limit);
    return Response.json(page, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    if (isProblem(error)) {
      return Response.json({ title: error.title, code: error.code }, { status: error.status });
    }
    return Response.json({ title: "We could not reach the Seed backend." }, { status: 502 });
  }
}
