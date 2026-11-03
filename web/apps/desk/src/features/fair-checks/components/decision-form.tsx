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
import { RadioGroup, RadioGroupItem } from "@paypal-seed/ui/components/radio-group";
import { toast } from "@paypal-seed/ui/components/sonner";
import { Textarea } from "@paypal-seed/ui/components/textarea";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { cn } from "@paypal-seed/ui/lib/utils";
import { useActionState, useId, useState } from "react";
import { type DecisionResult, recordDecision } from "../actions";
import { type FairCheckCard, MAX_TERM_MONTHS } from "../model";

type Choice = "APPROVED" | "ADJUSTED" | "DECLINED";

const CHOICES: { value: Choice; label: string; hint: string }[] = [
  { value: "APPROVED", label: "Approve as suggested", hint: "Use the amount and term the check sized." },
  { value: "ADJUSTED", label: "Adjust", hint: "A smaller amount, or a longer term." },
  { value: "DECLINED", label: "Decline", hint: "No loan at this time." },
];

const initial: DecisionResult = { status: "idle" };

export interface DecisionFormProps {
  card: FairCheckCard;
  /** Made on the server for this view: a retry of the same submission reuses it. */
  idempotencyKey: string;
  /** Called after a decision is saved, so the queue can refresh the row. */
  onDecided?: () => void;
}

/** What the member will be told, from the structured fields and what the officer typed. Nothing here is computed. */
function MemberPreview({
  choice,
  card,
  amount,
  term,
}: {
  choice: Choice;
  card: FairCheckCard;
  amount: string;
  term: string;
}) {
  if (choice === "DECLINED") {
    return (
      <div className="flex flex-col gap-2">
        <p>We cannot offer a loan right now. Here is why, and what would help:</p>
        <ul className="flex list-disc flex-col gap-1 pl-5">
          {card.plainLanguageReasons.slice(0, 3).map((reason) => (
            <li key={reason}>{reason}</li>
          ))}
        </ul>
      </div>
    );
  }
  if (choice === "ADJUSTED") {
    const cents = /^\d{1,9}(\.\d{1,2})?$/.test(amount) ? Math.round(Number(amount) * 100) : null;
    return (
      <p>
        {cents === null || !term
          ? "Enter an amount and a term to see the offer."
          : `We can offer ${formatMoney(cents)} over ${term} months. Seed works out the monthly payment when the offer is made.`}
      </p>
    );
  }
  return (
    <p>
      We can offer {formatMoney(card.suggestedLoanCents)} over {card.suggestedTermMonths} months, about{" "}
      {formatMoney(card.suggestedMonthlyInstalmentCents)} a month.
    </p>
  );
}

/**
 * Approve as suggested, adjust within the safe limits, or decline, with a required reason. The decision is confirmed
 * in a dialog that shows what the member will see. The server checks the same limits again.
 */
export function DecisionForm({ card, idempotencyKey, onDecided }: DecisionFormProps) {
  const formId = useId();
  const [choice, setChoice] = useState<Choice>(card.recommended ? "APPROVED" : "DECLINED");
  const [amount, setAmount] = useState("");
  const [term, setTerm] = useState("");
  const [reason, setReason] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  const [state, formAction, pending] = useActionState(async (previous: DecisionResult, formData: FormData) => {
    const result = await recordDecision(previous, formData);
    if (result.status === "ok") {
      toast.success(result.message);
      setConfirming(false);
      onDecided?.();
    }
    return result;
  }, initial);

  const maxDollars = card.suggestedLoanCents / 100;

  const review = () => {
    if (reason.trim().length < 3) {
      setLocalError("Give a reason, in a sentence.");
      return;
    }
    if (choice === "ADJUSTED") {
      const dollars = Number(amount);
      const months = Number(term);
      if (!/^\d{1,9}(\.\d{1,2})?$/.test(amount) || dollars < 1 || dollars > maxDollars) {
        setLocalError(`Enter an amount from $1 up to ${formatMoney(card.suggestedLoanCents)}.`);
        return;
      }
      if (!Number.isInteger(months) || months < card.suggestedTermMonths || months > MAX_TERM_MONTHS) {
        setLocalError(`Enter a term from ${card.suggestedTermMonths} to ${MAX_TERM_MONTHS} months.`);
        return;
      }
    }
    setLocalError(null);
    setConfirming(true);
  };

  const chosen = CHOICES.find((c) => c.value === choice);

  return (
    <form
      id={formId}
      action={formAction}
      className="flex flex-col gap-5"
      onKeyDown={(event) => {
        // Enter in a field must not save: a decision is saved only from the confirm dialog.
        if (event.key === "Enter" && event.target instanceof HTMLInputElement) {
          event.preventDefault();
        }
      }}
    >
      <input type="hidden" name="assessmentId" value={card.assessmentId} />
      <input type="hidden" name="key" value={idempotencyKey} />
      <input type="hidden" name="decision" value={choice} />
      {choice === "ADJUSTED" ? (
        <>
          <input type="hidden" name="adjustedAmount" value={amount} />
          <input type="hidden" name="adjustedTermMonths" value={term} />
        </>
      ) : null}
      <input type="hidden" name="reason" value={reason} />

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-2 text-sm font-medium text-ink">Your decision</legend>
        <RadioGroup value={choice} onValueChange={(value) => setChoice(value as Choice)} className="grid gap-2">
          {CHOICES.map((option) => (
            <Label
              key={option.value}
              htmlFor={`${formId}-${option.value}`}
              className={cn(
                "flex min-h-11 cursor-pointer items-start gap-3 rounded-xl border border-hairline bg-surface px-4 py-3 text-sm transition-colors",
                "has-[[data-state=checked]]:border-ink has-[[data-state=checked]]:bg-canvas-soft",
              )}
            >
              <RadioGroupItem id={`${formId}-${option.value}`} value={option.value} className="mt-0.5" />
              <span className="flex flex-col gap-0.5">
                <span className="font-medium text-ink">{option.label}</span>
                <span className="font-normal text-muted-ink">{option.hint}</span>
              </span>
            </Label>
          ))}
        </RadioGroup>
      </fieldset>

      {choice === "ADJUSTED" ? (
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${formId}-amount`}>Amount (US dollars)</Label>
            <Input
              id={`${formId}-amount`}
              inputMode="decimal"
              autoComplete="off"
              value={amount}
              onChange={(event) => setAmount(event.target.value)}
              aria-describedby={`${formId}-limits`}
              className="h-11"
            />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${formId}-term`}>Term (months)</Label>
            <Input
              id={`${formId}-term`}
              inputMode="numeric"
              autoComplete="off"
              value={term}
              onChange={(event) => setTerm(event.target.value)}
              aria-describedby={`${formId}-limits`}
              className="h-11"
            />
          </div>
          <p id={`${formId}-limits`} className="text-xs text-muted-ink sm:col-span-2">
            Safe limits: at most {formatMoney(card.suggestedLoanCents)} (what the check sized as affordable), and a term from{" "}
            {card.suggestedTermMonths} to {MAX_TERM_MONTHS} months.
          </p>
        </div>
      ) : null}

      <div className="flex flex-col gap-2">
        <Label htmlFor={`${formId}-reason`}>Reason</Label>
        <Textarea
          id={`${formId}-reason`}
          rows={3}
          maxLength={500}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="Write it so a colleague, or the member, could read it."
        />
        <p className="text-xs text-muted-ink">{reason.length} / 500. Saved with the decision.</p>
      </div>

      <div aria-live="polite" className="min-h-5">
        {localError ? (
          <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
            {localError}
          </p>
        ) : state.status === "error" && !confirming ? (
          <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
            {state.message}
          </p>
        ) : null}
      </div>

      <Button type="button" className="h-11 w-full" onClick={review}>
        Review decision
      </Button>

      <AlertDialog open={confirming} onOpenChange={(next) => (pending ? undefined : setConfirming(next))}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Save this decision?</AlertDialogTitle>
            <AlertDialogDescription asChild>
              <div className="flex flex-col gap-3 text-sm text-body">
                <p>
                  <span className="font-medium text-ink">{chosen?.label}.</span> It is recorded under your name and
                  cannot be undone from here.
                </p>
                <div className="flex flex-col gap-1 rounded-xl bg-canvas-soft p-3">
                  <p className="text-xs font-medium text-muted-ink">What the member will see</p>
                  <MemberPreview choice={choice} card={card} amount={amount} term={term} />
                </div>
              </div>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div aria-live="polite" className="min-h-5">
            {state.status === "error" ? (
              <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
                {state.message}
              </p>
            ) : null}
          </div>
          <AlertDialogFooter>
            <AlertDialogCancel type="button" disabled={pending}>
              Go back
            </AlertDialogCancel>
            <Button type="submit" form={formId} disabled={pending} className="h-11 sm:h-10">
              {pending ? "Saving…" : "Save decision"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </form>
  );
}
