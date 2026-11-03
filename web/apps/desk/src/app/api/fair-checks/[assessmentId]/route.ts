import { isProblem } from "@paypal-seed/api-client";
import type { NextRequest } from "next/server";
import { z } from "zod";
import { describeProblem } from "@/features/applications/server/problems";
import { newIdempotencyKey } from "@/features/hardship/server/keys";
import { assessmentById } from "@/features/fair-checks/server/queries";
import { getSession } from "@/server/session";

/**
 * One fair check for the queue's side sheet, with a fresh idempotency key for the decision form. The key is made
 * here, on the server, so one open sheet is one user action; the browser only echoes it back.
 */
export async function GET(_request: NextRequest, context: { params: Promise<{ assessmentId: string }> }) {
  if (!(await getSession())) {
    return Response.json({ title: "Please sign in again." }, { status: 401 });
  }
  const { assessmentId } = await context.params;
  if (!z.uuid().safeParse(assessmentId).success) {
    return Response.json({ title: "That fair check could not be found." }, { status: 404 });
  }
  try {
    const card = await assessmentById(assessmentId);
    if (!card) {
      return Response.json({ title: "That fair check could not be found." }, { status: 404 });
    }
    return Response.json({ card, idempotencyKey: newIdempotencyKey() }, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    if (isProblem(error)) {
      return Response.json({ title: describeProblem(error), code: error.code }, { status: error.status });
    }
    return Response.json({ title: "We could not reach the Seed backend." }, { status: 502 });
  }
}
