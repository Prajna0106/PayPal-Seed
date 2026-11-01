"use client";

import type { Locale } from "@paypal-seed/i18n";
import { Button } from "@paypal-seed/ui/components/button";
import { useActionState } from "react";
import { declineOffer, type DeclineState } from "../actions";
import { words } from "../copy";

/** One tap to say no. The plan stays exactly as it is. */
export function DeclineForm({ loanId, caseId, attemptKey, locale }: { loanId: string; caseId: string; attemptKey: string; locale: Locale }) {
  const t = words(locale);
  const [state, action, pending] = useActionState<DeclineState, FormData>(declineOffer, { status: "idle" });

  if (state.status === "declined") {
    return (
      <p role="status" className="text-sm text-body">
        {t.declined}
      </p>
    );
  }
  return (
    <form action={action} className="flex flex-col items-start gap-2">
      <input type="hidden" name="loanId" value={loanId} />
      <input type="hidden" name="caseId" value={caseId} />
      <input type="hidden" name="attemptKey" value={attemptKey} />
      <Button type="submit" variant="ghost" disabled={pending} aria-busy={pending}>
        {t.decline}
      </Button>
      {state.status === "error" ? (
        <p role="alert" className="text-sm text-danger">
          {state.message}
        </p>
      ) : null}
    </form>
  );
}
