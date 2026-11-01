"use client";

import { type Locale, translator } from "@paypal-seed/i18n";
import { Button } from "@paypal-seed/ui/components/button";
import { Label } from "@paypal-seed/ui/components/label";
import { Textarea } from "@paypal-seed/ui/components/textarea";
import { Loader2 } from "lucide-react";
import { useActionState, useId, useState } from "react";
import { type AskState, askForHelp } from "../actions";

const MAX = 500;

/** The member asks for help themselves. The optional note only sorts the request; it is never shown to an AI. */
export function AskHelpForm({ loanId, locale }: { loanId: string; locale: Locale }) {
  const t = translator(locale);
  const id = useId();
  const [length, setLength] = useState(0);
  const [state, action, pending] = useActionState<AskState, FormData>(askForHelp, { status: "idle" });
  const es = locale === "es";

  return (
    <form action={action} className="flex flex-col gap-4">
      <input type="hidden" name="loanId" value={loanId} />
      <input type="hidden" name="lang" value={locale} />
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-reason`} className="text-ink">
          {t("hardship.askReason")}
        </Label>
        <Textarea
          id={`${id}-reason`}
          name="reasonText"
          maxLength={MAX}
          rows={4}
          aria-describedby={`${id}-hint ${id}-count`}
          onChange={(event) => setLength(event.currentTarget.value.length)}
          className="bg-surface"
        />
        <div className="flex justify-between gap-4 text-xs text-muted-ink">
          <p id={`${id}-hint`}>{t("hardship.askReasonHint")}</p>
          <p id={`${id}-count`} aria-live="polite" className="tabular-nums">
            {length}/{MAX}
          </p>
        </div>
      </div>
      {state.status === "error" ? (
        <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
          {state.message}
        </p>
      ) : null}
      <Button type="submit" disabled={pending} aria-busy={pending} className="self-start">
        {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
        {es ? "Pedir ayuda" : "Ask for help"}
      </Button>
    </form>
  );
}
