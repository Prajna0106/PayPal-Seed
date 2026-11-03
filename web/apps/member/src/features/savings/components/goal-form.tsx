"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { Loader2 } from "lucide-react";
import { useActionState, useId, useState } from "react";
import { type CreateGoalState, createGoal } from "../actions";
import { centsToField, parseDollars, type Wallet } from "../model";
import { CoachPanel } from "./coach-panel";

export interface GoalFormProps {
  /** Made on the server when the page rendered; see `createGoal` for how it becomes the Idempotency-Key. */
  attemptKey: string;
  initialName: string;
  initialWallet: Wallet;
  language: "en" | "es";
}

const WALLETS: { value: Wallet; title: string; line: string }[] = [
  { value: "PAYPAL", title: "PayPal", line: "A monthly plan you agree to once" },
  { value: "VENMO", title: "Venmo", line: "You add money when you like" },
];

/** Name, monthly amount, optional target and wallet. The monthly plan is agreed at PayPal after this form. */
export function GoalForm({ attemptKey, initialName, initialWallet, language }: GoalFormProps) {
  const id = useId();
  const [state, action, pending] = useActionState<CreateGoalState, FormData>(createGoal, { status: "idle" });
  const [name, setName] = useState(initialName);
  const [monthly, setMonthly] = useState("");
  const [target, setTarget] = useState("");
  const [wallet, setWallet] = useState<Wallet>(initialWallet);
  const fields = state.status === "error" ? state.fields : {};

  return (
    <form action={action} className="flex flex-col gap-8" noValidate>
      <input type="hidden" name="attemptKey" value={attemptKey} />

      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-name`} className="text-ink">
          What are you saving for?
        </Label>
        <Input
          id={`${id}-name`}
          name="name"
          required
          maxLength={60}
          autoComplete="off"
          placeholder="Emergency fund, school fees, a new stove"
          value={name}
          onChange={(event) => setName(event.currentTarget.value)}
          aria-invalid={fields.name ? true : undefined}
          aria-describedby={fields.name ? `${id}-name-error` : undefined}
          className="h-11 bg-surface"
        />
        <FieldError id={`${id}-name-error`} message={fields.name} />
      </div>

      <fieldset className="flex flex-col gap-3">
        <legend className="mb-1 text-sm font-medium text-ink">Where will the money come from?</legend>
        <div className="grid gap-3 sm:grid-cols-2">
          {WALLETS.map((option) => (
            <label
              key={option.value}
              className="relative flex min-h-14 cursor-pointer flex-col justify-center gap-0.5 rounded-xl border border-hairline bg-surface px-4 py-3 has-[:checked]:border-ink has-[:focus-visible]:ring-[3px] has-[:focus-visible]:ring-ring/50"
            >
              <input
                type="radio"
                name="wallet"
                value={option.value}
                checked={wallet === option.value}
                onChange={() => setWallet(option.value)}
                className="sr-only"
              />
              <span className="text-sm font-medium text-ink">{option.title}</span>
              <span className="text-xs text-body">{option.line}</span>
            </label>
          ))}
        </div>
        <p className="text-sm text-body" aria-live="polite">
          {wallet === "VENMO"
            ? "Venmo cannot send a monthly payment on its own, so you add money to this goal yourself with a top-up whenever you like."
            : "PayPal takes your monthly amount for you. You agree to it once at PayPal, and you can pause any time."}
        </p>
      </fieldset>

      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-monthly`} className="text-ink">
          How much each month?
        </Label>
        <div className="relative">
          <span aria-hidden="true" className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-muted-ink">
            $
          </span>
          <Input
            id={`${id}-monthly`}
            name="monthly"
            required
            inputMode="decimal"
            autoComplete="off"
            placeholder="25"
            value={monthly}
            onChange={(event) => setMonthly(event.currentTarget.value)}
            aria-invalid={fields.monthly ? true : undefined}
            aria-describedby={`${id}-monthly-hint${fields.monthly ? ` ${id}-monthly-error` : ""}`}
            className="h-11 bg-surface pl-7"
          />
        </div>
        <p id={`${id}-monthly-hint`} className="text-xs text-muted-ink">
          From $5 a month.{wallet === "PAYPAL" ? " Whole dollars only." : ""}
        </p>
        <FieldError id={`${id}-monthly-error`} message={fields.monthly} />
      </div>

      <CoachPanel
        goalName={name}
        targetCents={parseDollars(target)}
        language={language}
        onApply={(cents) => setMonthly(centsToField(cents))}
      />

      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-target`} className="text-ink">
          A target (optional)
        </Label>
        <div className="relative">
          <span aria-hidden="true" className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-muted-ink">
            $
          </span>
          <Input
            id={`${id}-target`}
            name="target"
            inputMode="decimal"
            autoComplete="off"
            placeholder="300"
            value={target}
            onChange={(event) => setTarget(event.currentTarget.value)}
            aria-invalid={fields.target ? true : undefined}
            aria-describedby={`${id}-target-hint${fields.target ? ` ${id}-target-error` : ""}`}
            className="h-11 bg-surface pl-7"
          />
        </div>
        <p id={`${id}-target-hint`} className="text-xs text-muted-ink">
          Leave it empty and we will aim for about a year of saving. Your money is yours either way.
        </p>
        <FieldError id={`${id}-target-error`} message={fields.target} />
      </div>

      {state.status === "error" && state.message ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
          {state.message}
        </p>
      ) : null}

      <div className="flex flex-col gap-3">
        <Button type="submit" size="lg" disabled={pending} aria-busy={pending} className="self-start">
          {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
          {wallet === "PAYPAL" ? "Continue to PayPal" : "Start saving"}
        </Button>
        <p className="text-xs text-muted-ink">
          {wallet === "PAYPAL"
            ? "Nothing is taken until you agree at PayPal. If you change your mind there, nothing happens."
            : "Nothing is taken from you. Money moves only when you add it."}
        </p>
      </div>
    </form>
  );
}

function FieldError({ id, message }: { id: string; message: string | undefined }) {
  return message ? (
    <p id={id} role="alert" className="text-sm text-danger">
      {message}
    </p>
  ) : null;
}
