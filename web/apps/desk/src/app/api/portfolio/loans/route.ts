import { isProblem } from "@paypal-seed/api-client";
import type { NextRequest } from "next/server";
import { z } from "zod";
import { BUCKET_ORDER, SORT_COLUMNS, STATUS_ORDER } from "@/features/portfolio/model";
import { loanPage } from "@/features/portfolio/server/queries";
import { getSession } from "@/server/session";

/** Cursors are `base64url(json).base64url(hmac)`: refuse anything else before it reaches the backend. */
const CURSOR = /^[A-Za-z0-9_-]{1,2048}\.[A-Za-z0-9_-]{1,256}$/;

const flag = z.enum(["true", "false"]).transform((value) => value === "true");

const query = z.object({
  sort: z
    .templateLiteral([z.enum(SORT_COLUMNS), ":", z.enum(["asc", "desc"])])
    .optional(),
  status: z.array(z.enum(STATUS_ORDER)).default([]),
  agingBucket: z.array(z.enum(BUCKET_ORDER)).default([]),
  autopay: flag.optional(),
  hardship: flag.optional(),
  cursor: z.string().regex(CURSOR).optional(),
  limit: z.coerce.number().int().min(1).max(200).default(100),
});

/**
 * The loan grid's data source. The grid asks this route for one keyset page at a time; the route asks the backend
 * as the signed-in officer, so identity headers never leave the server. Sort and filters are allow-listed here and
 * again by the backend; the cursor is opaque and only ever passed back.
 */
export async function GET(request: NextRequest) {
  if (!(await getSession())) {
    return Response.json({ title: "Please sign in again." }, { status: 401 });
  }
  const params = request.nextUrl.searchParams;
  const parsed = query.safeParse({
    sort: params.get("sort") ?? undefined,
    status: params.getAll("status"),
    agingBucket: params.getAll("agingBucket"),
    autopay: params.get("autopay") ?? undefined,
    hardship: params.get("hardship") ?? undefined,
    cursor: params.get("cursor") ?? undefined,
    limit: params.get("limit") ?? undefined,
  });
  if (!parsed.success) {
    return Response.json({ title: "Unknown sort or filter." }, { status: 400 });
  }
  try {
    const page = await loanPage(parsed.data);
    return Response.json(page, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    if (isProblem(error)) {
      return Response.json({ title: error.title, code: error.code }, { status: error.status });
    }
    return Response.json({ title: "We could not reach the Seed backend." }, { status: 502 });
  }
}
