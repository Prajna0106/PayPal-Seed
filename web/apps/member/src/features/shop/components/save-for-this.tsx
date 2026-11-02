"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { CircleCheck, ExternalLink, Loader2, PiggyBank } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { useActionState, useId } from "react";
import { type SaveState, saveForProduct } from "../actions";
import type { PlanOption } from "../model";

interface Props {
  productId: string;
  options: PlanOption[];
  /** Made on the server for this render, so a double click or a retry is one request. */
  attemptKey: string;
}

/** The member picks how fast to save; nothing starts until they press the button and agree at PayPal. */
export function SaveForThis({ productId, options, attemptKey }: Props) {
  const id = useId();
  const [state, action, pending] = useActionState<SaveState, FormData>(saveForProduct, { status: "idle" });

  if (state.status === "saved") {
    return (
      <div role="status" className="flex flex-col gap-4 rounded-2xl border border-hairline bg-success-soft p-5">
        <p className="flex items-center gap-2 text-base font-medium text-ink">
          <CircleCheck className="size-5 text-success" aria-hidden="true" />
          Your savings goal is ready
        </p>
        {state.approvalUrl ? (
          <>
            <p className="text-sm text-body">
              One last step: agree to the monthly saving of {formatMoney(state.monthlyDollars * 100)} at PayPal. Nothing is taken until you do, and you can pause or stop whenever you like.
            </p>
            <div className="flex flex-wrap gap-2">
              <Button asChild>
                <a href={state.approvalUrl} rel="noopener noreferrer">
                  Agree at PayPal
                  <ExternalLink aria-hidden="true" />
                </a>
              </Button>
              <Button asChild variant="outline">
                <Link href={`/app/savings/${state.goalId}` as Route}>See this goal</Link>
              </Button>
            </div>
          </>
        ) : (
          <div className="flex flex-wrap gap-2">
            <Button asChild>
              <Link href={`/app/savings/${state.goalId}` as Route}>See this goal</Link>
            </Button>
            <Button asChild variant="outline">
              <Link href={"/app/shop/goals" as Route}>Your product goals</Link>
            </Button>
          </div>
        )}
      </div>
    );
  }

  return (
    <form action={action} className="flex flex-col gap-5">
      <input type="hidden" name="productId" value={productId} />
      <input type="hidden" name="attemptKey" value={attemptKey} />
      <fieldset className="flex flex-col gap-3">
        <legend className="text-base font-medium text-ink">How would you like to save?</legend>
        <p id={`${id}-hint`} className="text-sm text-body">
          Pick a pace that feels comfortable. You can change it or pause any time.
        </p>
        <div className="grid gap-3 sm:grid-cols-3" aria-describedby={`${id}-hint`}>
          {options.map((option, index) => (
            <label
              key={option.months}
              className="flex min-h-11 cursor-pointer flex-col gap-1 rounded-xl border border-hairline bg-surface p-4 transition-colors has-[:checked]:border-ink has-[:focus-visible]:ring-[3px] has-[:focus-visible]:ring-ring/50"
            >
              <input
                type="radio"
                name="months"
                value={option.months}
                defaultChecked={index === Math.min(1, options.length - 1)}
                className="sr-only"
              />
              <span className="text-display-sm text-ink">{formatMoney(option.monthlyDollars * 100)} a month</span>
              <span className="text-sm text-body">about {option.months} {option.months === 1 ? "month" : "months"}</span>
            </label>
          ))}
        </div>
      </fieldset>
      <div aria-live="polite">
        {state.status === "error" ? (
          <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
            {state.message}
          </p>
        ) : null}
      </div>
      <div className="flex flex-col gap-2">
        <Button type="submit" size="lg" disabled={pending} aria-busy={pending} className="self-start">
          {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : <PiggyBank aria-hidden="true" />}
          Save for this
        </Button>
        <p className="text-xs text-muted-ink">Saving is your choice, and so is whether to buy. The money stays yours.</p>
      </div>
    </form>
  );
}
