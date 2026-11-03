"use client";

import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@paypal-seed/ui/components/alert-dialog";
import { Button } from "@paypal-seed/ui/components/button";
import { Loader2, Pause, Play } from "lucide-react";
import { useActionState, useState } from "react";
import { pauseGoal, resumeGoal, type ToggleState } from "../actions";

/**
 * Pause asks first and says what it means. The streak rule is the backend's: one missed month in any three keeps the
 * streak, so a short pause is safe and a longer one can reset it. Resume goes straight ahead.
 */
export function PauseResume({ goalId, paused }: { goalId: string; paused: boolean }) {
  return paused ? <ResumeControl goalId={goalId} /> : <PauseControl goalId={goalId} />;
}

function PauseControl({ goalId }: { goalId: string }) {
  const [open, setOpen] = useState(false);
  const [state, action, pending] = useActionState<ToggleState, FormData>(pauseGoal, { status: "idle" });

  return (
    <AlertDialog open={open && state.status !== "done"} onOpenChange={(next) => !pending && setOpen(next)}>
      <AlertDialogTrigger asChild>
        <Button variant="outline">
          <Pause aria-hidden="true" />
          Pause saving
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <form action={action} className="flex flex-col gap-4">
          <input type="hidden" name="goalId" value={goalId} />
          <AlertDialogHeader>
            <AlertDialogTitle className="font-display text-2xl font-light">Pause saving?</AlertDialogTitle>
            <AlertDialogDescription asChild>
              <div className="flex flex-col gap-3 text-body">
                <p className="text-ink">No money is taken while you are paused, and what you have saved stays yours.</p>
                <p>
                  A paused month breaks no promise to us. Your saving streak lets you skip one month in any three. Pausing
                  for longer can reset the streak, which can slow down when a loan opens up for you.
                </p>
                <p>You can start again whenever you like.</p>
              </div>
            </AlertDialogDescription>
          </AlertDialogHeader>
          {state.status === "error" ? (
            <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
              {state.message}
            </p>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogCancel type="button" disabled={pending}>
              Keep saving
            </AlertDialogCancel>
            <Button type="submit" disabled={pending} aria-busy={pending}>
              {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
              Pause
            </Button>
          </AlertDialogFooter>
        </form>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function ResumeControl({ goalId }: { goalId: string }) {
  const [state, action, pending] = useActionState<ToggleState, FormData>(resumeGoal, { status: "idle" });

  return (
    <form action={action} className="flex flex-col items-start gap-2">
      <input type="hidden" name="goalId" value={goalId} />
      <Button type="submit" disabled={pending} aria-busy={pending}>
        {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : <Play aria-hidden="true" />}
        Resume saving
      </Button>
      <p className="text-xs text-muted-ink">If PayPal needs you to agree again, we will take you there.</p>
      <p role="alert" className="text-sm text-danger empty:hidden">
        {state.status === "error" ? state.message : null}
      </p>
    </form>
  );
}
