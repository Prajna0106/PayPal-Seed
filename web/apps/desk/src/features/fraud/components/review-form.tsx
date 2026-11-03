"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Label } from "@paypal-seed/ui/components/label";
import { RadioGroup, RadioGroupItem } from "@paypal-seed/ui/components/radio-group";
import { toast } from "@paypal-seed/ui/components/sonner";
import { Textarea } from "@paypal-seed/ui/components/textarea";
import { cn } from "@paypal-seed/ui/lib/utils";
import { Check, ShieldAlert } from "lucide-react";
import { type FormEvent, startTransition, useActionState, useEffect, useState } from "react";
import { type ReviewResult, reviewFlag } from "../actions";

export interface ReviewFormProps {
  flagId: string;
  /** Created on the server for this render. After a conflict the action hands back a fresh one. */
  idempotencyKey: string;
}

const initial: ReviewResult = { status: "idle" };

const OPTIONS = [
  { value: "CLEAR", label: "Clear", hint: "Nothing wrong: there is an ordinary explanation.", icon: <Check aria-hidden="true" /> },
  { value: "CONFIRM", label: "Confirm", hint: "The evidence holds up. A note is required.", icon: <ShieldAlert aria-hidden="true" /> },
] as const;

/** Clear or confirm a flag, once. The backend writes the audit entry. */
export function ReviewForm({ flagId, idempotencyKey }: ReviewFormProps) {
  const [state, formAction, pending] = useActionState(reviewFlag, initial);
  const [decision, setDecision] = useState<"CLEAR" | "CONFIRM">("CLEAR");
  // Every answer carries the key to use next; before the first answer it is the one made for this render.
  const key = state.status === "idle" || state.status === "ok" ? idempotencyKey : state.nextKey;

  useEffect(() => {
    if (state.status === "ok") {
      toast.success(state.message);
    }
  }, [state]);

  // A plain action would clear the note after an error; keep what the officer wrote.
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const formData = new FormData(event.currentTarget);
    formData.set("decision", decision);
    startTransition(() => formAction(formData));
  };

  return (
    <form onSubmit={submit} className="flex flex-col gap-5">
      <input type="hidden" name="flagId" value={flagId} />
      <input type="hidden" name="key" value={key} />

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-2 text-sm font-medium text-ink">Your review</legend>
        <RadioGroup value={decision} onValueChange={(value) => setDecision(value as "CLEAR" | "CONFIRM")} className="grid gap-2">
          {OPTIONS.map((option) => (
            <Label
              key={option.value}
              htmlFor={`review-${option.value}`}
              className={cn(
                "flex min-h-11 cursor-pointer items-start gap-3 rounded-xl border border-hairline bg-surface px-4 py-3 text-sm transition-colors",
                "has-[[data-state=checked]]:border-ink has-[[data-state=checked]]:bg-canvas-soft",
              )}
            >
              <RadioGroupItem id={`review-${option.value}`} value={option.value} className="mt-0.5" />
              <span className="flex flex-col gap-0.5">
                <span className="flex items-center gap-1.5 font-medium text-ink [&_svg]:size-4">
                  {option.icon}
                  {option.label}
                </span>
                <span className="font-normal text-muted-ink">{option.hint}</span>
              </span>
            </Label>
          ))}
        </RadioGroup>
      </fieldset>

      <div className="flex flex-col gap-2">
        <Label htmlFor="review-note">
          Note{" "}
          <span className="font-normal text-muted-ink">{decision === "CONFIRM" ? "(required)" : "(optional, but it helps the next officer)"}</span>
        </Label>
        <Textarea
          id="review-note"
          name="note"
          maxLength={1000}
          rows={4}
          required={decision === "CONFIRM"}
          placeholder="What did you check, and what did you find?"
        />
      </div>

      <div aria-live="polite" className="min-h-5">
        {state.status === "error" ? (
          <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
            {state.message}
          </p>
        ) : null}
      </div>

      <Button type="submit" disabled={pending} className="min-h-11 w-full">
        {pending ? "Recording…" : decision === "CLEAR" ? "Clear flag" : "Confirm flag"}
      </Button>
      <p className="text-xs text-muted-ink">A flag is reviewed once and the decision goes in the audit log. New evidence later opens a new flag.</p>
    </form>
  );
}
