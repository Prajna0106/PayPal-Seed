"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Loader2, Send } from "lucide-react";
import { useActionState } from "react";
import { type FormState, submitApplication } from "../actions";
import { FormMessage } from "./form-message";

const IDLE: FormState = { status: "idle" };

/** One button, no checkbox. The key is made on the server for this rendered page, so a double tap sends one application. */
export function SubmitForm({ assessmentId, attemptKey, dueDay }: { assessmentId: string; attemptKey: string; dueDay: number | null }) {
  const [state, action, pending] = useActionState<FormState, FormData>(submitApplication, IDLE);
  return (
    <form action={action} className="flex flex-col gap-3">
      <input type="hidden" name="assessmentId" value={assessmentId} />
      <input type="hidden" name="attemptKey" value={attemptKey} />
      <input type="hidden" name="dueDay" value={dueDay ?? ""} />
      <Button type="submit" size="lg" className="h-12 self-start" disabled={pending} aria-busy={pending}>
        {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : <Send aria-hidden="true" />}
        Submit application
      </Button>
      <FormMessage state={state} />
    </form>
  );
}
