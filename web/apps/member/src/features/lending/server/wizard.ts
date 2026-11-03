import "server-only";

import { z } from "zod";
import { type Eligibility, isStepId, MIN_LOAN_DOLLARS, type StepId, type WizardParams } from "../model";

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);
const whole = z.coerce.number().int();

function int(value: string | string[] | undefined, min: number, max: number): number | undefined {
  const raw = first(value);
  if (raw === undefined || raw === "") return undefined;
  const parsed = whole.min(min).max(max).safeParse(raw);
  return parsed.success ? parsed.data : undefined;
}

/** Which step the URL asks for, and what the member has typed so far. Anything malformed is simply dropped. */
export function readWizard(searchParams: SearchParams): { step: StepId; params: WizardParams } {
  const rawStep = first(searchParams.step);
  const assessment = z.uuid().safeParse(first(searchParams.assessment));
  const purpose = first(searchParams.purpose)?.trim().slice(0, 120);
  return {
    step: isStepId(rawStep) ? rawStep : "eligibility",
    params: {
      amount: int(searchParams.amount, 1, 1_000_000),
      term: int(searchParams.term, 1, 120),
      purpose: purpose || undefined,
      dueDay: int(searchParams.dueDay, 1, 28),
      assessment: assessment.success ? assessment.data : undefined,
    },
  };
}

export interface Limits {
  minDollars: number;
  maxDollars: number;
  minTerm: number;
  maxTerm: number;
}

/** The backend's limits as the fields need them. `maxLoanCents` is whole dollars in cents; it is shown, not worked out. */
export function limitsOf(eligibility: Eligibility): Limits {
  return {
    minDollars: MIN_LOAN_DOLLARS,
    maxDollars: Math.max(MIN_LOAN_DOLLARS, Math.floor(eligibility.maxLoanCents / 100)),
    minTerm: eligibility.minTermMonths,
    maxTerm: eligibility.maxTermMonths,
  };
}

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

/** The member's typed values pulled inside the limits (a URL can say anything). `null` until all three are present. */
export function clampedChoice(params: WizardParams, limits: Limits): (Required<Pick<WizardParams, "amount" | "term" | "purpose">> & Pick<WizardParams, "dueDay">) | null {
  if (params.amount === undefined || params.term === undefined || !params.purpose) {
    return null;
  }
  return {
    amount: clamp(params.amount, limits.minDollars, limits.maxDollars),
    term: clamp(params.term, limits.minTerm, limits.maxTerm),
    purpose: params.purpose,
    dueDay: params.dueDay,
  };
}
