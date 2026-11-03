"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { Loader2, Sparkles } from "lucide-react";
import { type KeyboardEvent, useId, useState } from "react";
import { type CoachResult, suggestPlan } from "../actions";

export interface CoachPanelProps {
  goalName: string;
  targetCents: number | null;
  language: "en" | "es";
  /** Called with the suggested monthly amount in cents when the member chooses to use it. */
  onApply: (cents: number) => void;
}

/**
 * Optional help with "how much?". The member says what comes in and goes out; the numbers come back as fields
 * computed by code, and the one sentence the assistant writes is labelled as such. Nothing here is saved.
 */
export function CoachPanel({ goalName, targetCents, language, onApply }: CoachPanelProps) {
  const id = useId();
  const [income, setIncome] = useState("");
  const [expenses, setExpenses] = useState("");
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<CoachResult | null>(null);

  // Enter in these fields asks the coach; it must not submit the goal form they sit inside.
  function askOnEnter(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Enter") {
      event.preventDefault();
      void ask();
    }
  }

  async function ask() {
    setPending(true);
    setResult(null);
    try {
      setResult(await suggestPlan({ goalName, income, expenses, targetCents, language }));
    } finally {
      setPending(false);
    }
  }

  return (
    <details className="group rounded-2xl border border-hairline bg-canvas-soft p-5">
      <summary className="flex min-h-11 cursor-pointer list-none items-center gap-2 text-sm font-medium text-ink [&::-webkit-details-marker]:hidden">
        <Sparkles className="size-4 text-muted-ink" aria-hidden="true" />
        Not sure how much? Ask the coach
      </summary>

      <div className="mt-4 flex flex-col gap-4">
        <p className="text-sm text-body">
          Tell us roughly what comes in and goes out each month. We use it only to make this suggestion, and we do not save it.
        </p>
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-income`} className="text-ink">
              Money in each month
            </Label>
            <Input
              id={`${id}-income`}
              inputMode="decimal"
              autoComplete="off"
              placeholder="1200"
              value={income}
              onChange={(event) => setIncome(event.currentTarget.value)}
              onKeyDown={askOnEnter}
              className="h-11 bg-surface"
            />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-expenses`} className="text-ink">
              Costs each month (optional)
            </Label>
            <Input
              id={`${id}-expenses`}
              inputMode="decimal"
              autoComplete="off"
              placeholder="900"
              value={expenses}
              onChange={(event) => setExpenses(event.currentTarget.value)}
              onKeyDown={askOnEnter}
              className="h-11 bg-surface"
            />
          </div>
        </div>
        <Button type="button" variant="outline" onClick={ask} disabled={pending} aria-busy={pending} className="self-start">
          {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
          Suggest an amount
        </Button>

        <div aria-live="polite" className="flex flex-col gap-4">
          {result?.ok === false ? (
            <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
              {result.message}
            </p>
          ) : null}
          {result?.ok ? <Suggestion result={result} onApply={onApply} /> : null}
        </div>
      </div>
    </details>
  );
}

function Suggestion({ result, onApply }: { result: Extract<CoachResult, { ok: true }>; onApply: (cents: number) => void }) {
  return (
    <div className="flex flex-col gap-4 rounded-xl border border-hairline bg-surface p-4">
      <div className="flex flex-col gap-1">
        <p className="text-sm text-muted-ink">Suggested saving</p>
        <p className="font-display text-3xl font-light text-ink tabular-nums">{formatMoney(result.suggestedCents)} a month</p>
        {result.unlockedLoanCents !== null && result.instalmentCents !== null ? (
          <p className="text-sm text-body">
            After {result.streakMonths} months of saving you could ask for a loan of up to {formatMoney(result.unlockedLoanCents)},
            paid back at {formatMoney(result.instalmentCents)} a month over {result.termMonths} months, at 0%.
          </p>
        ) : null}
      </div>
      <div className="flex flex-col gap-1 border-l-2 border-hairline-strong pl-3">
        <p className="text-xs font-medium text-muted-ink">{result.modelGenerated ? "Written by our assistant" : "Standard summary"}</p>
        <p className="text-sm text-body">{result.message}</p>
      </div>
      <Button type="button" onClick={() => onApply(result.suggestedCents)} className="self-start">
        Use {formatMoney(result.suggestedCents)} a month
      </Button>
    </div>
  );
}
