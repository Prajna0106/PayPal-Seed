import "server-only";

import { type LoanSummary, type ScheduleView, unwrap } from "@paypal-seed/api-client";
import { z } from "zod";
import { internalApi, memberApiFor } from "@/server/api";

/**
 * Seeds a fresh "Ana" story through the backend's internal endpoints (the same steps as the run guide): a $1,200
 * loan over 8 months due on the 20th, consented weekly income that drops 52% in the last four weeks, the demo
 * clock moved to eight days before the first due date, then the hardship scan and the outreach sweep. The backend
 * computes everything; this only drives it.
 */

const PRINCIPAL_CENTS = 120_000;
const INSTALMENTS = 8;
const DUE_DAY = 20;
const USUAL_WEEK_CENTS = 20_000;
const LEAN_WEEK_CENTS = 9_600;
const SCAN_LEAD_DAYS = 8;

export interface SeededStory {
  memberId: string;
  loanId: string;
}

export async function seedAnaStory(): Promise<SeededStory> {
  const internal = internalApi();
  const wallToday = programToday();

  let memberId = crypto.randomUUID();
  let loan = await register(memberId, wallToday);
  let schedule = await scheduleOf(memberId, loan);

  // The shared demo clock may already be ahead of the wall clock (earlier demos moved it). Disbursing on the
  // business date keeps the first instalment in front of us; the probe loan stays with an unused member id.
  if (schedule.businessDate && schedule.businessDate > wallToday) {
    memberId = crypto.randomUUID();
    loan = await register(memberId, schedule.businessDate);
    schedule = await scheduleOf(memberId, loan);
  }

  const businessDate = required(schedule.businessDate, "businessDate");
  const firstDue = required(schedule.instalments?.[0]?.dueDate, "first due date");
  const scanDate = maxDate(addDays(firstDue, -SCAN_LEAD_DAYS), businessDate);

  const advanceDays = daysBetween(businessDate, scanDate);
  if (advanceDays > 0) {
    await unwrap(internal.POST("/internal/demo-clock/advance", { params: { query: { days: advanceDays } } }));
  }

  const weeks = Array.from({ length: 13 }, (_, i) => ({
    weekStart: addDays(scanDate, -91 + 7 * i),
    inflowCents: i < 9 ? USUAL_WEEK_CENTS : LEAN_WEEK_CENTS,
  }));
  await unwrap(
    internal.PUT("/internal/underwriting/members/{memberId}/income", {
      params: { path: { memberId } },
      body: { consent: true, weeks },
    }),
  );

  let after: string | undefined;
  for (let page = 0; page < 50; page++) {
    const result = await unwrap(
      internal.POST("/internal/jobs/hardship/scan", {
        body: { shardGroup: 0, shardCount: 1, limit: 500, ...(after ? { after } : {}) },
      }),
    );
    if (result.done || !result.nextAfter) {
      break;
    }
    after = result.nextAfter;
  }
  // Releases the outreach now instead of waiting for the agent deadline (no model key means templates).
  await unwrap(internal.POST("/internal/jobs/hardship/sweep"));

  return { memberId, loanId: required(loan.loanId, "loanId") };
}

/** The internal contract does not declare this response body yet, so it is validated here at runtime. */
const registeredLoan = z.object({ loanId: z.uuid() }).loose();

async function register(memberId: string, disbursedOn: string): Promise<LoanSummary> {
  const body: unknown = await unwrap(
    internalApi().POST("/internal/loans/disbursed", {
      body: {
        memberId,
        principalCents: PRINCIPAL_CENTS,
        instalmentCount: INSTALMENTS,
        dueDay: DUE_DAY,
        disbursedOn,
        disbursementRef: `demo-ana-${crypto.randomUUID()}`,
      },
    }),
  );
  return registeredLoan.parse(body);
}

async function scheduleOf(memberId: string, loan: LoanSummary): Promise<ScheduleView> {
  return unwrap(
    memberApiFor(memberId).GET("/api/v1/loans/{loanId}/schedule", {
      params: { path: { loanId: required(loan.loanId, "loanId") } },
    }),
  );
}

function required<T>(value: T | undefined | null, name: string): T {
  if (value === undefined || value === null) {
    throw new Error(`The backend response is missing ${name}.`);
  }
  return value;
}

/** Today's date in the program's time zone, as a plain business date. */
function programToday(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "America/Los_Angeles" }).format(new Date());
}

function toUtc(iso: string): number {
  const [y, m, d] = iso.split("-").map(Number);
  return Date.UTC(y ?? 1970, (m ?? 1) - 1, d ?? 1);
}

function addDays(iso: string, days: number): string {
  return new Date(toUtc(iso) + days * 86_400_000).toISOString().slice(0, 10);
}

function daysBetween(from: string, to: string): number {
  return Math.round((toUtc(to) - toUtc(from)) / 86_400_000);
}

function maxDate(a: string, b: string): string {
  return a > b ? a : b;
}
