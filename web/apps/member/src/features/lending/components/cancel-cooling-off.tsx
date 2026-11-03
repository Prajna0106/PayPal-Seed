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
import { Loader2, Undo2 } from "lucide-react";
import { useState, useTransition } from "react";
import { cancelApplication, type FormState } from "../actions";
import { FormMessage } from "./form-message";

/** Returns the loan inside the 48-hour window, after the member confirms. Nothing is owed afterwards. */
export function CancelCoolingOff({ applicationId }: { applicationId: string }) {
  const [open, setOpen] = useState(false);
  const [pending, start] = useTransition();
  const [state, setState] = useState<FormState>({ status: "idle" });

  function confirm() {
    start(async () => {
      const result = await cancelApplication(applicationId);
      setState(result);
      if (result.status === "ok") {
        setOpen(false);
      }
    });
  }

  return (
    <div className="flex flex-col gap-3">
      <AlertDialog open={open} onOpenChange={setOpen}>
        <AlertDialogTrigger asChild>
          <Button variant="outline" className="h-11 self-start">
            <Undo2 aria-hidden="true" />
            Return this loan
          </Button>
        </AlertDialogTrigger>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Return this loan?</AlertDialogTitle>
            <AlertDialogDescription>
              The loan is cancelled and nothing is owed. There is no fee. You can apply again whenever you are ready.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <FormMessage state={state} />
          <AlertDialogFooter>
            <AlertDialogCancel className="h-11">Keep the loan</AlertDialogCancel>
            <Button className="h-11" onClick={confirm} disabled={pending} aria-busy={pending}>
              {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
              Yes, return it
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      {open ? null : <FormMessage state={state} />}
    </div>
  );
}
