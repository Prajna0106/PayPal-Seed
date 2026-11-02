"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@paypal-seed/ui/components/dialog";
import { Label } from "@paypal-seed/ui/components/label";
import { RadioGroup, RadioGroupItem } from "@paypal-seed/ui/components/radio-group";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@paypal-seed/ui/components/select";
import { toast } from "@paypal-seed/ui/components/sonner";
import { Textarea } from "@paypal-seed/ui/components/textarea";
import { cn } from "@paypal-seed/ui/lib/utils";
import { Check, Gavel, X } from "lucide-react";
import { useActionState, useCallback, useEffect, useRef, useState } from "react";
import { type ActionResult, decideCoverRequest } from "../actions";
import { type CoverProgress as CoverProgressModel, DECISION_REASONS } from "../model";
import { CoverProgress } from "./cover-progress";

export interface DecideDialogProps {
  coverRequestId: string;
  /** Already formatted, for example "$50". Shown in the title only. */
  amountLabel: string;
  /** Created on the server for this render; the action returns the next one after each answer. */
  idempotencyKey: string;
  /** Who has signed and when it is due, named and formatted on the server. */
  progress: CoverProgressModel;
}

const initial: ActionResult = { status: "idle" };

/** Maker and checker decide here. The time the dialog was open is sent so rushed decisions can be spotted. */
export function DecideDialog({ coverRequestId, amountLabel, idempotencyKey, progress }: DecideDialogProps) {
  const [open, setOpen] = useState(false);
  const close = useCallback(() => setOpen(false), []);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline" className="min-h-11 sm:min-h-8">
          <Gavel aria-hidden="true" />
          Decide
        </Button>
      </DialogTrigger>
      <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Decide on {amountLabel} cover</DialogTitle>
          <DialogDescription>
            Two different officers sign: a maker first, then a checker. The fund pays the loan, not the member.
          </DialogDescription>
        </DialogHeader>
        <CoverProgress progress={progress} className="rounded-xl border border-hairline bg-canvas-soft p-3" />
        <DecideForm coverRequestId={coverRequestId} initialKey={idempotencyKey} onDone={close} />
      </DialogContent>
    </Dialog>
  );
}

function DecideForm({ coverRequestId, initialKey, onDone }: { coverRequestId: string; initialKey: string; onDone: () => void }) {
  const [state, formAction, pending] = useActionState(decideCoverRequest, initial);
  const [decision, setDecision] = useState<"APPROVE" | "REJECT">("APPROVE");
  // Every answer carries the key to use next; before the first answer it is the one made for this render.
  const key = state.status === "idle" ? initialKey : state.nextKey;
  const openedAt = useRef<number | null>(null);

  useEffect(() => {
    openedAt.current = performance.now();
  }, []);

  useEffect(() => {
    if (state.status === "idle") {
      return;
    }
    if (state.status === "ok") {
      toast.success(state.message);
      onDone();
    }
  }, [state, onDone]);

  const submit = (formData: FormData) => {
    const viewed = openedAt.current === null ? 0 : Math.round(performance.now() - openedAt.current);
    formData.set("evidenceViewedMs", String(viewed));
    formData.set("decision", decision);
    formAction(formData);
  };

  return (
    <form action={submit} className="flex flex-col gap-5">
      <input type="hidden" name="coverRequestId" value={coverRequestId} />
      <input type="hidden" name="key" value={key} />

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-2 text-sm font-medium text-ink">Decision</legend>
        <RadioGroup value={decision} onValueChange={(value) => setDecision(value as "APPROVE" | "REJECT")} className="grid grid-cols-2 gap-2">
          {(
            [
              { value: "APPROVE", label: "Approve", icon: <Check aria-hidden="true" /> },
              { value: "REJECT", label: "Reject", icon: <X aria-hidden="true" /> },
            ] as const
          ).map((option) => (
            <Label
              key={option.value}
              htmlFor={`cover-${coverRequestId}-${option.value}`}
              className={cn(
                "flex min-h-11 cursor-pointer items-center gap-3 rounded-xl border border-hairline bg-surface px-4 py-3 text-sm transition-colors",
                "has-[[data-state=checked]]:border-ink has-[[data-state=checked]]:bg-canvas-soft",
              )}
            >
              <RadioGroupItem id={`cover-${coverRequestId}-${option.value}`} value={option.value} />
              <span className="flex items-center gap-1.5 [&_svg]:size-4">
                {option.icon}
                {option.label}
              </span>
            </Label>
          ))}
        </RadioGroup>
      </fieldset>

      <div className="flex flex-col gap-2">
        <Label htmlFor={`cover-${coverRequestId}-reason`}>Reason</Label>
        <Select name="reasonCode" required>
          <SelectTrigger id={`cover-${coverRequestId}-reason`} className="w-full">
            <SelectValue placeholder="Choose a reason" />
          </SelectTrigger>
          <SelectContent>
            {DECISION_REASONS.map((reason) => (
              <SelectItem key={reason.code} value={reason.code}>
                {reason.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="flex flex-col gap-2">
        <Label htmlFor={`cover-${coverRequestId}-note`}>
          Note <span className="font-normal text-muted-ink">(optional)</span>
        </Label>
        <Textarea id={`cover-${coverRequestId}-note`} name="note" maxLength={500} rows={3} placeholder="What did you check?" />
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
