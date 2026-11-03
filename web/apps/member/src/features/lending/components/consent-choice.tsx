"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { Loader2, ShieldCheck } from "lucide-react";
import { useActionState, useId, useTransition } from "react";
import { allowAndRunCheck, type FormState, runCheckWithOwnNumbers } from "../actions";
import { FormMessage } from "./form-message";

const RELINK = "/api/auth/paypal/start";
const IDLE: FormState = { status: "idle" };

export interface ChoiceFields {
  amount: number;
  term: number;
  purpose: string;
  dueDay: number | null;
}

function Hidden({ amount, term, purpose, dueDay }: ChoiceFields) {
  return (
    <>
      <input type="hidden" name="amount" value={amount} />
      <input type="hidden" name="term" value={term} />
      <input type="hidden" name="purpose" value={purpose} />
      <input type="hidden" name="dueDay" value={dueDay ?? ""} />
    </>
  );
}

/** Step 3: yes (reads PayPal income) or no thanks (type it in). Either way the member goes on to the fair check. */
export function ConsentChoice({ fields, alreadyAllowed }: { fields: ChoiceFields; alreadyAllowed: boolean }) {
  const id = useId();
  const [allowState, allow, allowPending] = useActionState<FormState, FormData>(allowAndRunCheck, IDLE);
  const [ownState, own, ownPending] = useActionState<FormState, FormData>(runCheckWithOwnNumbers, IDLE);
  const [, startTransition] = useTransition();

  return (
    <div className="flex flex-col gap-6">
      <form action={allow} className="flex flex-col gap-3">
        <Hidden {...fields} />
        <Button type="submit" size="lg" className="h-12 self-start" disabled={allowPending || ownPending} aria-busy={allowPending}>
          {allowPending ? <Loader2 className="animate-spin" aria-hidden="true" /> : <ShieldCheck aria-hidden="true" />}
          {alreadyAllowed ? "Continue to my fair check" : "Yes, read my PayPal income"}
        </Button>
        <FormMessage state={allowState} relinkHref={RELINK} />
      </form>

      <details className="group rounded-2xl border border-hairline bg-surface">
        <summary className="flex min-h-11 cursor-pointer list-none items-center px-5 py-3 text-[15px] font-medium text-ink focus-visible:rounded-2xl focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none">
          No thanks, I will enter my income myself
        </summary>
        {/* Submitted from a handler rather than `action`, so a refusal does not clear what the member typed. */}
        <form
          onSubmit={(event) => {
            event.preventDefault();
            const data = new FormData(event.currentTarget);
            startTransition(() => own(data));
          }}
          className="flex flex-col gap-4 border-t border-hairline px-5 py-5"
        >
          <Hidden {...fields} />
          <p className="text-sm text-body text-pretty">
            That is completely fine. Tell us roughly what you receive in a month and we will run the fair check on your numbers. A person at
            Seed may ask you about them. {alreadyAllowed ? "We will also stop reading your PayPal income." : ""}
          </p>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-income`} className="text-ink">
                Money you usually receive in a month ($)
              </Label>
              <Input id={`${id}-income`} name="statedIncome" type="number" inputMode="decimal" min={1} step="any" required className="h-11" />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-expenses`} className="text-ink">
                Bills you pay each month, roughly ($, optional)
              </Label>
              <Input id={`${id}-expenses`} name="statedExpenses" type="number" inputMode="decimal" min={0} step="any" className="h-11" />
            </div>
          </div>
          <Button type="submit" variant="outline" size="lg" className="h-12 self-start" disabled={allowPending || ownPending} aria-busy={ownPending}>
            {ownPending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
            Continue with my own numbers
          </Button>
          <FormMessage state={ownState} relinkHref={RELINK} />
        </form>
      </details>
    </div>
  );
}
