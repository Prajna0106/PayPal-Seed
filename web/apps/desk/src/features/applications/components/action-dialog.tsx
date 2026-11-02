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
import { toast } from "@paypal-seed/ui/components/sonner";
import { type ReactNode, useActionState, useState } from "react";
import type { ActionResult } from "../actions";

export interface ActionDialogProps {
  triggerLabel: string;
  triggerVariant?: "default" | "outline" | "destructive" | "ghost";
  triggerIcon?: ReactNode;
  title: string;
  description: ReactNode;
  submitLabel: string;
  pendingLabel: string;
  action: (previous: ActionResult, formData: FormData) => Promise<ActionResult>;
  /** Hidden inputs: the application id and the idempotency key made on the server for this render. */
  hidden: Record<string, string>;
  /** Extra fields (a reason), rendered inside the form. */
  children?: ReactNode;
  disabled?: boolean;
}

const initial: ActionResult = { status: "idle" };

/**
 * A confirm dialog around one server action. The dialog stays open on an error so the officer can read it and
 * correct the form; it closes on success and the page behind it re-reads (the action refreshes the route).
 */
export function ActionDialog({
  triggerLabel,
  triggerVariant = "default",
  triggerIcon,
  title,
  description,
  submitLabel,
  pendingLabel,
  action,
  hidden,
  children,
  disabled,
}: ActionDialogProps) {
  const [open, setOpen] = useState(false);
  const [state, formAction, pending] = useActionState(async (previous: ActionResult, formData: FormData) => {
    const result = await action(previous, formData);
    if (result.status === "ok") {
      toast.success(result.message);
      setOpen(false);
    }
    return result;
  }, initial);

  return (
    <AlertDialog open={open} onOpenChange={(next) => (pending ? undefined : setOpen(next))}>
      <AlertDialogTrigger asChild>
        <Button variant={triggerVariant} disabled={disabled} className="h-11 w-full">
          {triggerIcon}
          {triggerLabel}
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <form action={formAction} className="flex flex-col gap-4">
          {Object.entries(hidden).map(([name, value]) => (
            <input key={name} type="hidden" name={name} value={value} />
          ))}
          <AlertDialogHeader>
            <AlertDialogTitle>{title}</AlertDialogTitle>
            <AlertDialogDescription asChild>
              <div className="text-sm text-body">{description}</div>
            </AlertDialogDescription>
          </AlertDialogHeader>
          {children}
          <div aria-live="polite" className="min-h-5">
            {state.status === "error" ? (
              <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
                {state.message}
              </p>
            ) : null}
          </div>
          <AlertDialogFooter>
            <AlertDialogCancel type="button" disabled={pending}>
              Cancel
            </AlertDialogCancel>
            <Button type="submit" variant={triggerVariant === "destructive" ? "destructive" : "default"} disabled={pending} className="h-11 sm:h-10">
              {pending ? pendingLabel : submitLabel}
            </Button>
          </AlertDialogFooter>
        </form>
      </AlertDialogContent>
    </AlertDialog>
  );
}
