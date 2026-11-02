import { z } from "zod";
import { MIN_LOAN_DOLLARS } from "./model";

const emptyToUndefined = (value: unknown) => (value === "" || value === null ? undefined : value);

/** What the amount step sends along: whole dollars, a term in months, why, and an optional due day. */
export const loanChoiceSchema = z.object({
  amount: z.coerce.number({ error: "Choose an amount in whole dollars." }).int("Choose an amount in whole dollars.").min(MIN_LOAN_DOLLARS, `The smallest loan is $${MIN_LOAN_DOLLARS}.`),
  term: z.coerce.number({ error: "Choose how many months." }).int().min(3).max(18),
  purpose: z.string().trim().min(1, "Tell us what the loan is for.").max(120),
  dueDay: z.preprocess(emptyToUndefined, z.coerce.number().int().min(1).max(28).optional()),
});

/** Income typed by hand, for a member who prefers not to share it. Dollars, converted to cents once on the server. */
export const ownNumbersSchema = z.object({
  statedIncome: z.coerce
    .number({ error: "Enter what you usually receive in a month." })
    .positive("Enter what you usually receive in a month.")
    .max(1_000_000, "That looks too high. Please check it."),
  statedExpenses: z.preprocess(
    emptyToUndefined,
    z.coerce.number({ error: "Enter a number, or leave this empty." }).min(0).max(1_000_000, "That looks too high. Please check it.").optional(),
  ),
});

export const disputeSchema = z.object({
  assessmentId: z.uuid(),
  note: z.string().trim().min(1, "Please tell us what looks wrong.").max(1000, "Please keep it under 1,000 characters."),
});

export const submitSchema = z.object({
  assessmentId: z.uuid(),
  attemptKey: z.uuid(),
  dueDay: z.preprocess(emptyToUndefined, z.coerce.number().int().min(1).max(28).optional()),
});

/** The live payment preview: whole dollars and months from the amount step, checked again on the server. */
export const previewSchema = z.object({
  amount: z.number().int().min(1).max(1_000_000),
  term: z.number().int().min(1).max(60),
  dueDay: z.number().int().min(1).max(28).nullable(),
});

export const cancelSchema = z.object({ applicationId: z.uuid() });
