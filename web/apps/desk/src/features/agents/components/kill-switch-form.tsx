"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Label } from "@paypal-seed/ui/components/label";
import { RadioGroup, RadioGroupItem } from "@paypal-seed/ui/components/radio-group";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@paypal-seed/ui/components/select";
import { Textarea } from "@paypal-seed/ui/components/textarea";
import { cn } from "@paypal-seed/ui/lib/utils";
import { useActionState, useEffect, useState } from "react";
import { toast } from "@paypal-seed/ui/components/sonner";
import type { ActionResult } from "@/features/hardship/actions";
import { setKillSwitch } from "../actions";

type Mode = "LIVE" | "SHADOW" | "TEMPLATES_ONLY";

export const MODES: { value: Mode; label: string; description: string }[] = [
  {
    value: "LIVE",
    label: "Live",
    description: "The Guardian writes briefs and messages. Text reaches people only after all 12 checks pass.",
  },
  {
    value: "SHADOW",
    label: "Shadow",
    description: "The Guardian runs and its text is stored for comparison, but members and officers get templates.",
  },
  {
    value: "TEMPLATES_ONLY",
    label: "Templates only",
    description: "No model calls at all. Every message is a deterministic template; the safety net keeps working.",
  },
];

const initial: ActionResult = { status: "idle" };

export function KillSwitchForm({ current, isAdmin }: { current: Mode; isAdmin: boolean }) {
  const [state, formAction, pending] = useActionState(setKillSwitch, initial);
  const [mode, setMode] = useState<Mode>(current);

  useEffect(() => {
    if (state.status === "ok") {
      toast.success(state.message);
    }
  }, [state]);

  return (
    <form action={formAction} className="flex flex-col gap-6">
      <fieldset>
        <legend className="mb-3 text-sm font-medium text-ink">Mode</legend>
        <RadioGroup name="mode" value={mode} onValueChange={(value) => setMode(value as Mode)} className="grid gap-3 lg:grid-cols-3">
          {MODES.map((option) => (
            <Label
              key={option.value}
              htmlFor={`mode-${option.value}`}
              className={cn(
                "flex cursor-pointer flex-col items-start gap-2 rounded-xl border border-hairline bg-surface p-4 font-normal transition-colors",
                "has-[[data-state=checked]]:border-ink has-[[data-state=checked]]:bg-canvas-soft",
              )}
            >
              <span className="flex items-center gap-2 text-sm font-medium text-ink">
                <RadioGroupItem id={`mode-${option.value}`} value={option.value} />
                {option.label}
                {option.value === current ? <span className="text-xs font-normal text-muted-ink">(current)</span> : null}
              </span>
              <span className="text-sm text-body">{option.description}</span>
            </Label>
          ))}
        </RadioGroup>
      </fieldset>

      <div className="grid gap-4 md:grid-cols-[200px_minmax(0,1fr)]">
        <div className="flex flex-col gap-2">
          <Label htmlFor="scope">Scope</Label>
          <Select name="scope" defaultValue="GLOBAL">
            <SelectTrigger id="scope" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="GLOBAL">All agents</SelectItem>
              <SelectItem value="guardian">Guardian only</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor="reason">Reason</Label>
          <Textarea id="reason" name="reason" required minLength={3} maxLength={500} rows={2} placeholder="Why are you changing the mode?" />
        </div>
      </div>

      <div aria-live="polite" className="min-h-5">
        {state.status === "error" ? (
          <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
            {state.message}
          </p>
        ) : null}
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={pending || mode === current}>
          {pending ? "Saving…" : "Change mode"}
        </Button>
        {!isAdmin ? (
          <p className="text-sm text-muted-ink">
            You can make the agents safer. Moving back to a less restrictive mode needs an admin (Prajna Jeet Ojha).
          </p>
        ) : null}
      </div>
    </form>
  );
}
