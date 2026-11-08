"use client";

import { Badge } from "@paypal-seed/ui/components/badge";
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
    <section aria-labelledby="judge-access" className="rounded-3xl border border-hairline bg-surface p-6 shadow-soft sm:p-8">
      <div className="flex items-center justify-between gap-3">
        <h2 id="judge-access" className="text-display-md text-ink">
          Try each role
        </h2>
        <Badge variant="outline">Judge access</Badge>
      </div>
      <p className="mt-2 text-sm text-body">
        Test accounts for this demo. A loan needs two different people to approve it, so use the officer first and the
        checker second.
      </p>
      <ul className="mt-6 flex flex-col gap-3">
        {JUDGE_LOGINS.map((login) => (
          <li key={login.role} className="flex items-center justify-between gap-4 rounded-2xl border border-hairline-soft bg-canvas-soft p-4">
            <div className="min-w-0">
              <p className="font-medium text-ink">{login.role}</p>
              <p className="text-sm text-muted-ink">{login.does}</p>
              <p className="mt-2 font-mono text-xs break-all text-body">
                {login.email}
                <span aria-hidden="true" className="px-1.5 text-muted-ink">
                  /
                </span>
                {login.password}
              </p>
            </div>
            <Button type="button" size="sm" onClick={() => fill(login.email, login.password)}>
              Use
              <span className="sr-only"> the {login.role} account</span>
            </Button>
          </li>
        ))}
      </ul>
    </section>
  );
}
