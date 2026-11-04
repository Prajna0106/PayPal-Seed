import { z } from "zod";

export const MIN_MONTHLY_CENTS = 500;

/** Dollars typed by a person (`25`, `25.5`, `1,200` is not accepted), as whole cents. Strings keep floats out of the way. */
const money = (message: string) =>
  z
    .string()
    .trim()
    .regex(/^\d{1,7}(\.\d{1,2})?$/, message)
    .transform((value) => Math.round(Number(value) * 100));

const optionalMoney = (message: string) =>
  z
    .string()
    .trim()
    .transform((value) => (value === "" ? null : value))
    .pipe(z.union([z.null(), money(message)]));

const wallet = z.enum(["PAYPAL", "VENMO"]);

export const createGoalSchema = z
  .object({
    name: z.string().trim().min(1, "Give your goal a name.").max(60, "Please keep the name under 60 characters."),
    monthly: money("Enter an amount in dollars, like 25."),
    target: optionalMoney("Enter a target in dollars, like 300, or leave it empty."),
    wallet,
    // One per rendered form; see the action for how it becomes the Idempotency-Key.
    attemptKey: z.uuid(),
  })
  .superRefine((value, ctx) => {
    if (value.monthly < MIN_MONTHLY_CENTS) {
      ctx.addIssue({ code: "custom", path: ["monthly"], message: "The smallest monthly amount is $5." });
    } else if (value.wallet === "PAYPAL" && value.monthly % 100 !== 0) {
      ctx.addIssue({ code: "custom", path: ["monthly"], message: "PayPal saves whole dollars each month, like $25." });
    }
    if (value.target !== null && value.target <= 0) {
      ctx.addIssue({ code: "custom", path: ["target"], message: "A target needs to be more than $0." });
    }
  });

export const coachSchema = z.object({
  goalName: z.string().trim().max(60).optional(),
  income: money("Enter your monthly income in dollars.").refine((cents) => cents > 0, "Enter your monthly income in dollars."),
  expenses: optionalMoney("Enter your monthly costs in dollars, or leave it empty."),
  targetCents: z.number().int().positive().nullable(),
  language: z.enum(["en", "es"]),
});

export const goalIdSchema = z.object({ goalId: z.uuid() });

export const amountSchema = (message: string) => money(message).refine((cents) => cents > 0, message);

export const topUpSchema = z.object({
  goalId: z.uuid(),
  amount: amountSchema("Enter an amount in dollars, like 20."),
});

export const captureSchema = z.object({ goalId: z.uuid(), orderId: z.string().min(1).max(64) });

export const withdrawSchema = z.object({
  goalId: z.uuid(),
  amount: amountSchema("Enter an amount in dollars, like 20."),
  attemptKey: z.uuid(),
});

export const changeAmountSchema = z
  .object({
    goalId: z.uuid(),
    monthly: money("Enter an amount in dollars, like 25."),
    wallet,
    // One per rendered form; see the action for how it becomes the Idempotency-Key.
    attemptKey: z.uuid(),
  })
  .superRefine((value, ctx) => {
    if (value.monthly < MIN_MONTHLY_CENTS) {
      ctx.addIssue({ code: "custom", path: ["monthly"], message: "The smallest monthly amount is $5." });
    } else if (value.wallet === "PAYPAL" && value.monthly % 100 !== 0) {
      ctx.addIssue({ code: "custom", path: ["monthly"], message: "PayPal saves whole dollars each month, like $25." });
    }
  });

/** First message per field, for showing next to the input. */
export function fieldErrors(error: z.ZodError): Record<string, string> {
  const out: Record<string, string> = {};
  for (const issue of error.issues) {
    const field = String(issue.path[0] ?? "form");
    out[field] ??= issue.message;
  }
  return out;
}
