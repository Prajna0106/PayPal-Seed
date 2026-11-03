import { z } from "zod";
import { ASSESSMENT_STATUS_ORDER, type AssessmentStatus, MAX_TERM_MONTHS } from "./model";

export const fairCheckFilterModel = z
  .object({
    status: z.array(z.enum(ASSESSMENT_STATUS_ORDER as [AssessmentStatus, ...AssessmentStatus[]])).max(4),
    disputed: z.boolean(),
  })
  .partial()
  .strict();

/** The queue URL (`?status=PENDING&disputed=true`) read into the grid's first filters. */
export function readFairCheckSearch(params: Record<string, string | string[] | undefined>) {
  const raw = (key: string) => {
    const value = params[key];
    return Array.isArray(value) ? value[0] : value;
  };
  const statuses = (raw("status") ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter((value): value is AssessmentStatus => (ASSESSMENT_STATUS_ORDER as string[]).includes(value));
  return { status: [...new Set(statuses)], disputed: raw("disputed") === "true" };
}

const emptyToUndefined = (value: unknown) => (typeof value === "string" && value.trim() === "" ? undefined : value);

export const decisionForm = z
  .object({
    assessmentId: z.uuid(),
    key: z.uuid(),
    decision: z.enum(["APPROVED", "ADJUSTED", "DECLINED"], { message: "Choose approve, adjust or decline." }),
    /** Typed in dollars by the officer; converted to cents once, here. */
    adjustedAmount: z.preprocess(
      emptyToUndefined,
      z
        .string()
        .regex(/^\d{1,9}(\.\d{1,2})?$/, "Enter the amount in dollars, like 450 or 450.50.")
        .transform((dollars) => Math.round(Number(dollars) * 100))
        .optional(),
    ),
    adjustedTermMonths: z.preprocess(
      emptyToUndefined,
      z.coerce
        .number({ message: "Enter a term in months." })
        .int("Enter a whole number of months.")
        .min(3, "The shortest term is 3 months.")
        .max(MAX_TERM_MONTHS, `The longest term is ${MAX_TERM_MONTHS} months.`)
        .optional(),
    ),
    reason: z
      .string()
      .trim()
      .min(3, "Give a reason, in a sentence.")
      .max(500, "Keep the reason under 500 characters."),
  })
  .refine((value) => value.decision !== "ADJUSTED" || (value.adjustedAmount !== undefined && value.adjustedTermMonths !== undefined), {
    message: "Enter the adjusted amount and term.",
  });
