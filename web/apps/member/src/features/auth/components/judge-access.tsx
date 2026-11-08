"use client";

import { Badge } from "@paypal-seed/ui/components/badge";
import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { JUDGE_ACCOUNTS } from "../judge-accounts";

function CopyValue({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="flex items-center justify-between gap-2">
      <p className="min-w-0 text-xs">
        <span className="text-muted-ink">{label} </span>
        <span className="font-mono break-all text-ink">{value}</span>
      </p>
      <button
        type="button"
        aria-label={copied ? `${label} copied` : `Copy ${label.toLowerCase()}`}
        className="inline-flex size-8 shrink-0 items-center justify-center rounded-full border border-hairline text-body transition-colors hover:bg-surface-strong hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2"
        onClick={() => {
          void navigator.clipboard
            .writeText(value)
            .then(() => {
              setCopied(true);
              setTimeout(() => setCopied(false), 1500);
            })
            .catch(() => undefined);
        }}
      >
        {copied ? <Check aria-hidden="true" className="size-4" /> : <Copy aria-hidden="true" className="size-4" />}
      </button>
    </div>
  );
}

export function JudgeAccess() {
  return (
    <section aria-labelledby="judge-access" className="rounded-3xl border border-hairline bg-surface p-6 shadow-soft sm:p-8">
      <div className="flex items-center justify-between gap-3">
        <h2 id="judge-access" className="text-display-md text-ink">
          Test accounts
        </h2>
        <Badge variant="outline">Judge access</Badge>
      </div>
      <p className="mt-2 text-sm text-body">
        Choose Log in with PayPal, then sign in at PayPal with any of these sandbox accounts. No real money is used.
      </p>
      <ul className="mt-6 grid gap-3 sm:grid-cols-2">
        {JUDGE_ACCOUNTS.map((account) => (
          <li key={account.email} className="flex flex-col gap-2 rounded-2xl border border-hairline-soft bg-canvas-soft p-4">
            <div>
              <p className="font-medium text-ink">{account.name}</p>
              <p className="text-xs text-muted-ink">{account.note}</p>
            </div>
            <CopyValue label="Email" value={account.email} />
            <CopyValue label="Password" value={account.password} />
          </li>
        ))}
      </ul>
    </section>
  );
}
