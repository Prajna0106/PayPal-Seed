"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { RotateCw } from "lucide-react";
import { useActionState, useEffect } from "react";
import { toast } from "@paypal-seed/ui/components/sonner";
import { type ActionResult, retryExecution } from "../actions";

const initial: ActionResult = { status: "idle" };

export function RetryButton({ caseId }: { caseId: string }) {
  const [state, formAction, pending] = useActionState(retryExecution, initial);

  useEffect(() => {
    if (state.status === "ok") {
      toast.success(state.message);
    } else if (state.status === "error") {
      toast.error(state.message);
    }
  }, [state]);

  return (
    <form action={formAction}>
      <input type="hidden" name="caseId" value={caseId} />
      <Button type="submit" variant="outline" disabled={pending}>
        <RotateCw aria-hidden="true" />
        {pending ? "Retrying…" : "Retry applying the plan"}
      </Button>
    </form>
  );
}
