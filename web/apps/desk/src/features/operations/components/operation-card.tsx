"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { type ReactNode, useActionState, useEffect } from "react";
import { toast } from "@paypal-seed/ui/components/sonner";
import type { ActionResult } from "@/features/hardship/actions";
import { advanceDemoClock, runCloseOfBusiness, runHardshipScan, verifyAuditChain } from "../actions";

const initial: ActionResult = { status: "idle" };

function useResultToast(state: ActionResult) {
  useEffect(() => {
    if (state.status === "ok") {
      toast.success(state.message);
    } else if (state.status === "error") {
      toast.error(state.message);
    }
  }, [state]);
}

function Result({ state }: { state: ActionResult }) {
  return (
    <p aria-live="polite" className={state.status === "error" ? "min-h-5 text-sm text-danger" : "min-h-5 text-sm text-body"}>
      {state.status === "idle" ? "" : state.message}
    </p>
  );
}

function Shell({ title, description, icon, children }: { title: string; description: string; icon: ReactNode; children: ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 [&_svg]:size-4 [&_svg]:text-muted-ink">
          {icon}
          {title}
        </CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">{children}</CardContent>
    </Card>
  );
}

type NoInputAction = (previous: ActionResult) => Promise<ActionResult>;

const ACTIONS: Record<"verify" | "scan" | "cob", { action: NoInputAction; label: string; busy: string }> = {
  verify: { action: verifyAuditChain, label: "Verify audit chain", busy: "Verifying…" },
  scan: { action: runHardshipScan, label: "Run scan and sweep", busy: "Scanning…" },
  cob: { action: runCloseOfBusiness, label: "Run close of business", busy: "Closing…" },
};

export function RunOperation({
  kind,
  title,
  description,
  icon,
}: {
  kind: keyof typeof ACTIONS;
  title: string;
  description: string;
  icon: ReactNode;
}) {
  const spec = ACTIONS[kind];
  const [state, formAction, pending] = useActionState(spec.action, initial);
  useResultToast(state);
  return (
    <Shell title={title} description={description} icon={icon}>
      <form action={formAction}>
        <Button type="submit" variant="outline" disabled={pending}>
          {pending ? spec.busy : spec.label}
        </Button>
      </form>
      <Result state={state} />
    </Shell>
  );
}

export function AdvanceClock({ icon }: { icon: ReactNode }) {
  const [state, formAction, pending] = useActionState(advanceDemoClock, initial);
  useResultToast(state);
  return (
    <Shell
      title="Demo clock"
      description="Moves the shared business date forward for every role. It never moves back. Run close of business afterwards to age loans."
      icon={icon}
    >
      <form action={formAction} className="flex items-end gap-2">
        <div className="flex flex-col gap-2">
          <Label htmlFor="days">Days</Label>
          <Input id="days" name="days" type="number" inputMode="numeric" min={1} max={400} defaultValue={1} required className="w-28" />
        </div>
        <Button type="submit" variant="outline" disabled={pending}>
          {pending ? "Advancing…" : "Advance"}
        </Button>
      </form>
      <Result state={state} />
    </Shell>
  );
}
