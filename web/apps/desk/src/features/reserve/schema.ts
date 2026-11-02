import { z } from "zod";
import { DECISION_REASONS, RAISE_REASONS } from "./model";

/** "50", "50.5" or "50.25" dollars to whole cents, by string arithmetic so no float rounding can creep in. */
export function dollarsToCents(text: string): number | null {
  const match = /^(\d{1,7})(?:\.(\d{1,2}))?$/.exec(text.trim());
  if (!match) {
    return null;
  }
  const cents = Number(match[1]) * 100 + Number((match[2] ?? "").padEnd(2, "0"));
  return cents > 0 ? cents : null;
}

const codes = <T extends readonly { code: string }[]>(list: T) => list.map((r) => r.code) as [string, ...string[]];

const optionalUuid = z
  .string()
  .trim()
  .transform((value) => (value === "" ? undefined : value))
  .pipe(z.uuid("Use the full case reference (a UUID).").optional());

export const raiseSchema = z.object({
  memberId: z.uuid("Enter the member reference (a UUID)."),
  loanId: z.uuid("Enter the loan reference (a UUID)."),
  amount: z
    .string()
    .trim()
    .refine((value) => dollarsToCents(value) !== null, "Enter an amount in dollars, for example 50 or 49.50."),
  reasonCode: z.enum(codes(RAISE_REASONS), { message: "Choose a reason." }),
  hardshipCaseId: optionalUuid,
  note: z.string().trim().max(500, "Keep the note under 500 characters.").optional(),
  key: z.uuid(),
});

export const decideSchema = z.object({
  coverRequestId: z.uuid(),
  decision: z.enum(["APPROVE", "REJECT"], { message: "Choose approve or reject." }),
  reasonCode: z.enum(codes(DECISION_REASONS), { message: "Choose a reason." }),
  note: z.string().trim().max(500, "Keep the note under 500 characters.").optional(),
  evidenceViewedMs: z.coerce.number().int().min(0).max(86_400_000),
  key: z.uuid(),
});
