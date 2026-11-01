"use server";

import { isProblem, problemMessage, unwrap } from "@paypal-seed/api-client";
import { refresh } from "next/cache";
import { z } from "zod";
import type { ActionResult } from "@/features/hardship/actions";
import { adminInternalApi } from "@/server/api";
import { getSession, hasRole } from "@/server/session";

const NOT_ADMIN: ActionResult = { status: "error", message: "Operations need the ADMIN role. Switch persona to Prajna Jeet Ojha." };

async function asAdmin<T>(run: () => Promise<T>, done: (result: T) => string): Promise<ActionResult> {
  const session = await getSession();
  if (!session || !hasRole(session, "ADMIN")) {
    return NOT_ADMIN;
  }
  try {
    const result = await run();
    refresh();
    return { status: "ok", message: done(result) };
  } catch (error) {
    return {
      status: "error",
      message: isProblem(error) ? problemMessage(error) : "We could not reach the Seed backend.",
      ...(isProblem(error) ? { code: error.code } : {}),
    };
  }
}

/** Recomputes the governance audit hash chain; any break means a row was changed after it was written. */
export async function verifyAuditChain(_previous: ActionResult): Promise<ActionResult> {
  return asAdmin(
    async () => unwrap((await adminInternalApi()).POST("/internal/jobs/governance/verify-audit-chain")),
    (result) =>
      result.intact
        ? `Audit chain intact: ${result.entries ?? 0} entries verified.`
        : `Audit chain BROKEN at entry ${result.firstBrokenSeq ?? "?"}. Escalate now.`,
  );
}

const advanceForm = z.object({ days: z.coerce.number().int().min(1).max(400) });

/** Moves the shared demo business date forward. It never moves back. */
export async function advanceDemoClock(_previous: ActionResult, formData: FormData): Promise<ActionResult> {
  const parsed = advanceForm.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: "Days must be between 1 and 400." };
  }
  return asAdmin(
    async () => {
      const api = await adminInternalApi();
      // The contract declares no body; the backend returns the new business date.
      const result: unknown = await unwrap(
        api.POST("/internal/demo-clock/advance", { params: { query: { days: parsed.data.days } } }),
      );
      return (result ?? {}) as { businessDate?: string };
    },
    (result) => (result.businessDate ? `Business date is now ${result.businessDate}.` : "Demo clock advanced."),
  );
}

/** Runs the daily hardship scan over every shard, then fires due case timers (offers, SLAs, holds). */
export async function runHardshipScan(_previous: ActionResult): Promise<ActionResult> {
  return asAdmin(
    async () => {
      const api = await adminInternalApi();
      let after: string | undefined;
      let scanned = 0;
      let opened = 0;
      let failed = 0;
      for (let page = 0; page < 50; page++) {
        const result = await unwrap(
          api.POST("/internal/jobs/hardship/scan", {
            body: { shardGroup: 0, shardCount: 1, limit: 500, ...(after ? { after } : {}) },
          }),
        );
        scanned += result.scanned ?? 0;
        opened += result.opened ?? 0;
        failed += result.failed ?? 0;
        if (result.done || !result.nextAfter) {
          break;
        }
        after = result.nextAfter;
      }
      const sweep = await unwrap(api.POST("/internal/jobs/hardship/sweep", { params: { query: { limit: 500 } } }));
      return { scanned, opened, failed, fired: sweep.fired ?? 0 };
    },
    (r) => `Scanned ${r.scanned} loans, opened ${r.opened} case${r.opened === 1 ? "" : "s"}${r.failed ? `, ${r.failed} failed` : ""}; fired ${r.fired} timer${r.fired === 1 ? "" : "s"}.`,
  );
}

/** Closes every business date up to today, in order (the daily close's catch-up). */
export async function runCloseOfBusiness(_previous: ActionResult): Promise<ActionResult> {
  return asAdmin(
    async () => {
      const api = await adminInternalApi();
      // The contract declares no body; the backend returns the target date and the runs it closed.
      const result: unknown = await unwrap(api.POST("/internal/jobs/cob/catch-up", {}));
      return (result ?? {}) as { target?: string; runs?: unknown[] };
    },
    (r) => `Close of business caught up${r.target ? ` to ${r.target}` : ""}${r.runs ? `: ${r.runs.length} date${r.runs.length === 1 ? "" : "s"} closed` : ""}.`,
  );
}
