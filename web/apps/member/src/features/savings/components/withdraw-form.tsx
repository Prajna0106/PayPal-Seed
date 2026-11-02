"use client";

import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@paypal-seed/ui/components/alert-dialog";
import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { CheckCircle2, Loader2 } from "lucide-react";
import { useActionState, useId, useState } from "react";
import { withdraw, type WithdrawState } from "../actions";
import { parseDollars } from "../model";

export interface WithdrawFormProps {
  goalId: string;
  /** Made on the server when the page rendered; a new one arrives with each page refresh after a withdrawal. */
  attemptKey: string;
  /** Free money in the goal, in cents. */
  availableCents: number;
}

/** Take money out. The member confirms the exact amount first; the payout then shows as "on its way" below. */
export function WithdrawForm(props: WithdrawFormProps) {
  // A new round remounts the form, so its state starts clean for the next withdrawal.
  const [round, setRound] = useState(0);
  return <WithdrawFields key={round} {...props} onAnother={() => setRound((value) => value + 1)} />;
}

function WithdrawFields({ goalId, attemptKey, availableCents, onAnother }: WithdrawFormProps & { onAnother: () => void }) {
  const id = useId();
  const [state, action, pending] = useActionState<WithdrawState, FormData>(withdraw, { status: "idle" });
  const [amount, setAmount] = useState("");
  const [open, setOpen] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  const cents = parseDollars(amount);
  const serverError = state.status === "error" ? state.fields.amount : undefined;
  const error = localError ?? serverError;

  function review() {
    if (cents === null || cents <= 0) {
      setLocalError("Enter an amount in dollars, like 20.");
    } else if (cents > availableCents) {
      setLocalError(`You can take out up to ${formatMoney(availableCents)} right now.`);
    } else {
      setLocalError(null);
      setOpen(true);
    }
  }

  if (state.status === "sent") {
    return (
      <div role="status" aria-live="polite" className="flex flex-col items-start gap-3 rounded-2xl bg-success-soft p-5">
        <p className="inline-flex items-start gap-2 text-sm font-medium text-ink">
          <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-success" aria-hidden="true" />
          {formatMoney(state.amountCents)} is on its way to your account.
        </p>
        <p className="text-sm text-body">
          PayPal is sending it. You will see it as &ldquo;On its way&rdquo; below until it arrives, and it can take a little while.
        </p>
        <Button type="button" variant="outline" onClick={onAnother}>
          Take out another amount
        </Button>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex max-w-xs flex-col gap-2">
        <Label htmlFor={`${id}-amount`} className="text-ink">
          How much would you like to take out?
        </Label>
        <div className="relative">
          <span aria-hidden="true" className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-muted-ink">
            $
          </span>
          <Input
            id={`${id}-amount`}
            inputMode="decimal"
            autoComplete="off"
            value={amount}
            onChange={(event) => {
              setAmount(event.currentTarget.value);
              setLocalError(null);
            }}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                review();
              }
            }}
            aria-invalid={error ? true : undefined}
            aria-describedby={`${id}-hint${error ? ` ${id}-error` : ""}`}
            className="h-11 bg-surface pl-7"
          />
        </div>
        <p id={`${id}-hint`} className="text-xs text-muted-ink">
          You can take out up to {formatMoney(availableCents)} right now.
        </p>
        {error ? (
          <p id={`${id}-error`} role="alert" className="text-sm text-danger">
            {error}
          </p>
        ) : null}
      </div>

      <Button type="button" variant="outline" onClick={review} className="self-start">
        Take money out
      </Button>

      <AlertDialog open={open} onOpenChange={(next) => !pending && setOpen(next)}>
        <AlertDialogContent>
          <form action={action} className="flex flex-col gap-4">
            <input type="hidden" name="goalId" value={goalId} />
            <input type="hidden" name="attemptKey" value={attemptKey} />
            <input type="hidden" name="amount" value={amount} />
            <AlertDialogHeader>
              <AlertDialogTitle className="font-display text-2xl font-light">
                Take out {cents ? formatMoney(cents) : "this amount"}?
              </AlertDialogTitle>
              <AlertDialogDescription asChild>
                <div className="flex flex-col gap-3 text-body">
                  <p className="text-ink">We will send it to your account through PayPal.</p>
                  <p>
                    It shows as &ldquo;On its way&rdquo; until PayPal confirms. If it cannot be delivered, the money comes back to
                    this goal.
                  </p>
                </div>
              </AlertDialogDescription>
            </AlertDialogHeader>
            {state.status === "error" && (state.message || serverError) ? (
              <div role="alert" className="flex flex-col gap-2 rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
                <p>{state.message || serverError}</p>
                {state.needsSignIn ? (
                  // A plain link, not next/link: it starts the PayPal sign-in and must not be prefetched.
                  <a href="/api/auth/paypal/start" className="font-medium underline underline-offset-4">
                    Sign in with PayPal again
                  </a>
                ) : null}
              </div>
            ) : null}
            <AlertDialogFooter>
              <AlertDialogCancel type="button" disabled={pending}>
                Not now
              </AlertDialogCancel>
              <Button type="submit" disabled={pending} aria-busy={pending}>
                {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
                Take it out
              </Button>
            </AlertDialogFooter>
          </form>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
