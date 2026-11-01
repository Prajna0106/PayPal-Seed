"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Label } from "@paypal-seed/ui/components/label";
import { RadioGroup, RadioGroupItem } from "@paypal-seed/ui/components/radio-group";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@paypal-seed/ui/components/select";
import { Textarea } from "@paypal-seed/ui/components/textarea";
import { cn } from "@paypal-seed/ui/lib/utils";
import { Check, X } from "lucide-react";
import { useActionState, useEffect, useRef, useState } from "react";
import { toast } from "@paypal-seed/ui/components/sonner";
import { type ActionResult, decide } from "../actions";
import { REASON_CODES } from "../model";

export interface DecisionFormProps {
  caseId: string;
  /** Created on the server for this render; a retry of the same submission reuses it. */
  idempotencyKey: string;
  /** "maker" or "checker": only changes the wording; the backend decides the role. */
  step: "maker" | "checker";
}

const initial: ActionResult = { status: "idle" };

/**
 * Approve or reject with a reason. The time the evidence was on screen is sent with the decision so the desk can
 * spot decisions made too fast to be a real review.
 */
export function DecisionForm({ caseId, idempotencyKey, step }: DecisionFormProps) {
  const [state, formAction, pending] = useActionState(decide, initial);
  const [decision, setDecision] = useState<"APPROVE" | "REJECT">("APPROVE");
  const openedAt = useRef<number | null>(null);

  useEffect(() => {
    openedAt.current = performance.now();
  }, []);

  useEffect(() => {
    if (state.status === "ok") {
      toast.success(state.message);
    }
  }, [state]);

  const submit = (formData: FormData) => {
    const viewed = openedAt.current === null ? 0 : Math.round(performance.now() - openedAt.current);
    formData.set("evidenceViewedMs", String(viewed));
    formData.set("decision", decision);
    formAction(formData);
  };

  return (
    <form action={submit} className="flex flex-col gap-5" aria-describedby="decision-help">
      <input type="hidden" name="caseId" value={caseId} />
      <input type="hidden" name="key" value={idempotencyKey} />
      <p id="decision-help" className="text-sm text-body">
        {step === "checker"
          ? "You are the second officer. You must be a different person from the maker."
          : "You are the first officer (maker). Options B and C then need a second officer to check."}
      </p>

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-2 text-sm font-medium text-ink">Decision</legend>
        <RadioGroup
          value={decision}
          onValueChange={(value) => setDecision(value as "APPROVE" | "REJECT")}
          className="grid grid-cols-2 gap-2"
        >
          {(
            [
              { value: "APPROVE", label: "Approve", icon: <Check aria-hidden="true" /> },
              { value: "REJECT", label: "Reject", icon: <X aria-hidden="true" /> },
            ] as const
          ).map((option) => (
            <Label
              key={option.value}
              htmlFor={`decision-${option.value}`}
              className={cn(
                "flex cursor-pointer items-center gap-3 rounded-xl border border-hairline bg-surface px-4 py-3 text-sm transition-colors",
                "has-[[data-state=checked]]:border-ink has-[[data-state=checked]]:bg-canvas-soft",
              )}
            >
              <RadioGroupItem id={`decision-${option.value}`} value={option.value} />
              <span className="flex items-center gap-1.5 [&_svg]:size-4">
                {option.icon}
                {option.label}
              </span>
            </Label>
          ))}
        </RadioGroup>
      </fieldset>

      <div className="flex flex-col gap-2">
        <Label htmlFor="reasonCode">Reason</Label>
        <Select name="reasonCode" required>
          <SelectTrigger id="reasonCode" className="w-full">
            <SelectValue placeholder="Choose a reason" />
          </SelectTrigger>
          <SelectContent>
            {REASON_CODES.map((reason) => (
              <SelectItem key={reason.code} value={reason.code}>
                {reason.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="flex flex-col gap-2">
        <Label htmlFor="note">
          Note <span className="font-normal text-muted-ink">(optional)</span>
        </Label>
        <Textarea id="note" name="note" maxLength={500} rows={3} placeholder="What did you check?" />
      </div>

      <div aria-live="polite" className="min-h-5">
        {state.status === "error" ? (
          <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
            {state.message}
          </p>
        ) : null}
      </div>

      <Button type="submit" disabled={pending} className="w-full">
        {pending ? "Recording…" : decision === "APPROVE" ? "Approve" : "Reject"}
      </Button>
    </form>
  );
}
