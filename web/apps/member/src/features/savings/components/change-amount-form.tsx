"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { CheckCircle2, Loader2 } from "lucide-react";
import { useActionState, useId, useState } from "react";
import { type ChangeAmountState, changeMonthlyAmount } from "../actions";
import { centsToField, type Wallet } from "../model";

/** Change the monthly amount. PayPal sometimes asks the member to agree again; the action then sends them there. */
export function ChangeAmountForm({
  goalId,
  wallet,
  monthlyCents,
  attemptKey,
}: {
  goalId: string;
  wallet: Wallet;
  monthlyCents: number;
  /** Made on the server when the page rendered. */
  attemptKey: string;
}) {
  const id = useId();
  const [state, action, pending] = useActionState<ChangeAmountState, FormData>(changeMonthlyAmount, { status: "idle" });
  const [amount, setAmount] = useState(centsToField(monthlyCents));
  const error = state.status === "error" ? (state.fields.monthly ?? state.fields.form) : undefined;

  return (
    <form action={action} className="flex flex-col gap-3">
      <input type="hidden" name="goalId" value={goalId} />
      <input type="hidden" name="wallet" value={wallet} />
      <input type="hidden" name="attemptKey" value={attemptKey} />
      <div className="flex max-w-xs flex-col gap-2">
        <Label htmlFor={`${id}-monthly`} className="text-ink">
          New monthly amount
        </Label>
        <div className="relative">
          <span aria-hidden="true" className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-muted-ink">
            $
          </span>
          <Input
            id={`${id}-monthly`}
            name="monthly"
            inputMode="decimal"
            autoComplete="off"
            value={amount}
            onChange={(event) => setAmount(event.currentTarget.value)}
            aria-invalid={error ? true : undefined}
            aria-describedby={`${id}-hint${error ? ` ${id}-error` : ""}`}
            className="h-11 bg-surface pl-7"
          />
        </div>
        <p id={`${id}-hint`} className="text-xs text-muted-ink">
          {wallet === "PAYPAL" ? "Whole dollars, from $5. " : "From $5. "}
          You save {formatMoney(monthlyCents)} a month now. PayPal may ask you to agree to the new amount.
        </p>
      </div>

      <Button type="submit" variant="outline" disabled={pending} aria-busy={pending} className="min-h-11 self-start">
        {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
        Change amount
      </Button>

      <div aria-live="polite" className="flex flex-col gap-1 empty:hidden">
        {error ? (
          <p id={`${id}-error`} role="alert" className="text-sm text-danger">
            {error}
          </p>
        ) : null}
        {state.status === "error" && state.message ? (
          <p role="alert" className="text-sm text-danger">
            {state.message}
          </p>
        ) : null}
        {state.status === "done" ? (
          <p className="inline-flex items-start gap-2 text-sm font-medium text-ink">
            <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-success" aria-hidden="true" />
            Done. You will save {formatMoney(state.monthlyCents)} a month.
          </p>
        ) : null}
      </div>
    </form>
  );
}
