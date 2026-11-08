"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { JUDGE_LOGINS } from "../judge-logins";

/** The sign-in form's inputs are uncontrolled, so setting their values is enough: the form reads them when it submits. */
function fill(email: string, password: string) {
  const fields = { email, password } as const;
  for (const [id, value] of Object.entries(fields)) {
    const input = document.getElementById(id);
    if (input instanceof HTMLInputElement) {
      input.value = value;
    }
  }
  document.getElementById("password")?.focus();
}

export function JudgeAccess() {
  return (
    <section aria-labelledby="judge-access" className="rounded-2xl border border-dashed border-hairline-strong bg-surface/70 p-5">
      <h2 id="judge-access" className="text-lg font-medium text-ink">
        For judges: try each role
      </h2>
      <p className="mt-1 text-sm text-body">Test accounts for this demo. A loan needs two different people to approve it.</p>
      <ul className="mt-4 flex flex-col divide-y divide-hairline-soft">
        {JUDGE_LOGINS.map((login) => (
          <li key={login.role} className="flex flex-col gap-2 py-3 first:pt-0 last:pb-0 sm:flex-row sm:items-center sm:justify-between">
            <div className="min-w-0">
              <p className="font-medium text-ink">{login.role}</p>
              <p className="text-sm text-muted-ink">{login.does}</p>
              <p className="mt-1 font-mono text-xs break-all text-body">
                {login.email} / {login.password}
              </p>
            </div>
            <Button type="button" variant="outline" size="sm" onClick={() => fill(login.email, login.password)}>
              Fill in
            </Button>
          </li>
        ))}
      </ul>
    </section>
  );
}
