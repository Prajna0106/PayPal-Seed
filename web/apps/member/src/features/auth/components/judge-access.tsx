"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { useState } from "react";

/** A PayPal sandbox buyer account for the demo. Shown only when SEED_JUDGE_ACCESS is on. */
const SANDBOX_ACCOUNT = { email: "sb-whnt053695945@personal.example.com", password: "BE0@v++f" } as const;

function CopyRow({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="flex items-center justify-between gap-3">
      <p className="min-w-0 text-sm">
        <span className="text-muted-ink">{label} </span>
        <span className="font-mono text-xs break-all text-ink">{value}</span>
      </p>
      <Button
        type="button"
        variant="outline"
        size="sm"
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
        {copied ? "Copied" : "Copy"}
      </Button>
    </div>
  );
}

export function JudgeAccess() {
  return (
    <section aria-labelledby="judge-access" className="flex flex-col gap-3 rounded-2xl border border-dashed border-hairline-strong bg-surface/70 p-5">
      <div>
        <h2 id="judge-access" className="text-lg font-medium text-ink">
          For judges: a test account
        </h2>
        <p className="mt-1 text-sm text-body">
          Choose Log in with PayPal, then sign in with this PayPal sandbox account. No real money is used.
        </p>
      </div>
      <CopyRow label="Email" value={SANDBOX_ACCOUNT.email} />
      <CopyRow label="Password" value={SANDBOX_ACCOUNT.password} />
    </section>
  );
}
