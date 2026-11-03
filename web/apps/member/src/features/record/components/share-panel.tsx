"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { formatDateTime } from "@paypal-seed/ui/lib/format";
import { Check, Copy, Link2 } from "lucide-react";
import { useActionState, useId, useState } from "react";
import { allowSharing, createShare, type ConsentState, type ShareState } from "../actions";
import { DETAIL_CHOICES, EXPIRY_CHOICES } from "../model";

/** One sentence on what a partner sees, shown before any link exists. */
export function SharePanel({ sharingAllowed }: { sharingAllowed: boolean }) {
  return (
    <div className="flex flex-col gap-5 rounded-2xl border border-hairline bg-surface p-6">
      <p className="text-body">
        Whoever opens the link sees only what you choose below, at most your level, when your record began, loans
        repaid on plan and how often you pay on time. No name, no email and no loan amounts.
      </p>
      {sharingAllowed ? <CreateForm /> : <ConsentForm />}
    </div>
  );
}

function ConsentForm() {
  const [state, action, pending] = useActionState<ConsentState, FormData>(allowSharing, { status: "idle" });
  return (
    <form action={action} className="flex flex-col items-start gap-3 rounded-xl bg-canvas-soft p-5">
      <p className="text-sm text-body">
        Sharing is your choice. Until you allow it, no link can be made. You can stop sharing any link at any time.
      </p>
      <Button type="submit" disabled={pending} aria-busy={pending}>
        Allow sharing my record
      </Button>
      {state.status === "error" ? (
        <p role="alert" className="text-sm text-danger">
          {state.message}
        </p>
      ) : null}
    </form>
  );
}

function CreateForm() {
  const [state, action, pending] = useActionState<ShareState, FormData>(createShare, { status: "idle" });
  const expiryId = useId();
  return (
    <div className="flex flex-col gap-5">
      <form action={action} className="flex flex-col gap-5">
        <fieldset className="flex flex-col gap-3">
          <legend className="mb-1 text-sm font-medium text-ink">What the link shows</legend>
          <div className="grid gap-3 sm:grid-cols-3">
            {DETAIL_CHOICES.map((choice) => (
              <label
                key={choice.value}
                className="flex min-h-11 cursor-pointer flex-col gap-1 rounded-xl border border-hairline bg-surface p-4 has-[:checked]:border-ink has-[:focus-visible]:ring-[3px] has-[:focus-visible]:ring-ring/50"
              >
                <span className="flex items-center gap-2 text-sm font-medium text-ink">
                  <input
                    type="radio"
                    name="detail"
                    value={choice.value}
                    defaultChecked={choice.value === "SUMMARY"}
                    className="size-4 accent-[var(--color-ink)]"
                  />
                  {choice.title}
                </span>
                <span className="text-sm text-body">{choice.description}</span>
              </label>
            ))}
          </div>
        </fieldset>

        <div className="flex flex-col gap-2 sm:max-w-xs">
          <Label htmlFor={expiryId}>The link works for</Label>
          <select
            id={expiryId}
            name="expiresInDays"
            defaultValue="7"
            className="h-11 rounded-xl border border-hairline bg-surface px-3 text-sm text-ink outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50"
          >
            {EXPIRY_CHOICES.map((choice) => (
              <option key={choice.days} value={choice.days}>
                {choice.label}
              </option>
            ))}
          </select>
        </div>

        <div className="flex flex-col items-start gap-2">
          <Button type="submit" disabled={pending} aria-busy={pending}>
            <Link2 aria-hidden="true" />
            {pending ? "Creating..." : "Create a link"}
          </Button>
          <p role="status" aria-live="polite" className="text-sm text-danger empty:hidden">
            {state.status === "error" ? state.message : null}
          </p>
        </div>
      </form>

      {state.status === "created" ? <CreatedLink link={state.link} expiresAt={state.expiresAt} /> : null}
    </div>
  );
}

/** The link is shown once: the list afterwards only has status, because the backend never returns the token again. */
function CreatedLink({ link, expiresAt }: { link: string; expiresAt: string }) {
  const [copied, setCopied] = useState<"idle" | "done" | "manual">("idle");
  const inputId = useId();

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(link);
      setCopied("done");
    } catch {
      setCopied("manual");
    }
  };

  return (
    <div className="flex flex-col gap-3 rounded-xl border border-ink bg-canvas p-5" aria-live="polite">
      <Label htmlFor={inputId} className="text-sm font-medium text-ink">
        Your link is ready
      </Label>
      <div className="flex flex-col gap-2 sm:flex-row">
        <Input id={inputId} readOnly value={link} onFocus={(event) => event.currentTarget.select()} className="h-11 font-mono text-xs" />
        <Button type="button" onClick={copy} className="shrink-0">
          {copied === "done" ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
          {copied === "done" ? "Copied" : "Copy link"}
        </Button>
      </div>
      <p className="text-sm text-body">
        Copy it now: for your safety we show it only once. It works until {formatDateTime(expiresAt)}.
        {copied === "manual" ? " Your browser did not allow copying, so select the link above and copy it yourself." : ""}
      </p>
    </div>
  );
}
