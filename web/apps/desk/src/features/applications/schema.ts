import { z } from "zod";
import { type ApplicationStatus, STATUS_ORDER } from "./model";

/** The queue's filter model, as the client sends it and the route handler accepts it. Unknown keys are refused. */
export const applicationFilterModel = z
  .object({
    status: z.array(z.enum(STATUS_ORDER as [ApplicationStatus, ...ApplicationStatus[]])).max(STATUS_ORDER.length),
    slaBreached: z.boolean(),
  })
  .partial()
  .strict();

/** The queue URL (`?status=SUBMITTED,PENDING_SECOND_APPROVAL&slaBreached=true`) read into the grid's first filters. */
export function readApplicationSearch(params: Record<string, string | string[] | undefined>) {
  const raw = (key: string) => {
    const value = params[key];
    return Array.isArray(value) ? value[0] : value;
  };
  const statuses = (raw("status") ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter((value): value is ApplicationStatus => (STATUS_ORDER as string[]).includes(value));
  return { status: [...new Set(statuses)], slaBreached: raw("slaBreached") === "true" };
}

export const approveForm = z.object({ applicationId: z.uuid(), key: z.uuid() });

export const disburseForm = z.object({ applicationId: z.uuid(), key: z.uuid() });

export const syncPayoutInput = z.uuid();

export const rejectForm = z.object({
  applicationId: z.uuid(),
  key: z.uuid(),
  reason: z
    .string()
    .trim()
    .min(3, "Say why, in a sentence, so the next officer understands.")
    .max(500, "Keep the reason under 500 characters."),
});
