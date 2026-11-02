"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Label } from "@paypal-seed/ui/components/label";
import { Textarea } from "@paypal-seed/ui/components/textarea";
import { Loader2 } from "lucide-react";
import { useActionState, useId, useState } from "react";
import { disputeCheck, type FormState } from "../actions";
import { FormMessage } from "./form-message";

const MAX = 1000;
const IDLE: FormState = { status: "idle" };

/** A short note saying a number looks wrong. A person reads it; it never changes the result by itself. */
export function DisputeForm({ assessmentId }: { assessmentId: string }) {
  const id = useId();
  const [length, setLength] = useState(0);
  const [state, action, pending] = useActionState<FormState, FormData>(disputeCheck, IDLE);

  return (
    <details className="rounded-2xl border border-hairline bg-surface">
      <summary className="flex min-h-11 cursor-pointer list-none items-center px-5 py-3 text-[15px] font-medium text-ink focus-visible:rounded-2xl focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none">
        Something looks wrong
      </summary>
      <form action={action} className="flex flex-col gap-3 border-t border-hairline px-5 py-5">
        <input type="hidden" name="assessmentId" value={assessmentId} />
        <Label htmlFor={`${id}-note`} className="text-ink">
          What looks wrong?
        </Label>
        <Textarea
          id={`${id}-note`}
          name="note"
          rows={3}
          maxLength={MAX}
          required
          aria-describedby={`${id}-hint`}
          onChange={(event) => setLength(event.currentTarget.value.length)}
          className="bg-surface"
        />
        <div className="flex justify-between gap-4 text-xs text-muted-ink">
          <p id={`${id}-hint`}>A person at Seed reads this. Nothing is decided until they have.</p>
          <p aria-live="polite" className="tabular-nums">
            {length}/{MAX}
          </p>
        </div>
        <Button type="submit" variant="outline" className="h-11 self-start" disabled={pending} aria-busy={pending}>
          {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
          Send to a person
        </Button>
        <FormMessage state={state} />
      </form>
    </details>
  );
}
