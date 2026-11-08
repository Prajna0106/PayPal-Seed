"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { useActionState } from "react";
import { tryVerify, type TryState } from "../actions";

const INITIAL: TryState = { status: "idle" };

export function TryIt() {
  const [state, action, pending] = useActionState(tryVerify, INITIAL);
  return (
    <form action={action} className="rounded-2xl border border-hairline bg-surface p-5 sm:p-6">
      <label htmlFor="try-token" className="text-sm font-medium text-ink">
        Share token
      </label>
      <p id="try-hint" className="mt-1 text-sm text-muted-ink">
        Paste the part after /verify/ in a share link. Seed checks it from our server.
      </p>
      <div className="mt-3 flex flex-col gap-3 sm:flex-row">
        <input
          id="try-token"
          name="token"
          type="text"
          autoComplete="off"
          spellCheck={false}
          required
          aria-describedby="try-hint"
          className="min-h-11 w-full min-w-0 rounded-full border border-hairline-strong bg-canvas px-4 font-mono text-sm text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ink"
        />
        <Button type="submit" disabled={pending} className="min-h-11 sm:shrink-0">
          {pending ? "Checking…" : "Verify"}
        </Button>
      </div>
      <div aria-live="polite" className="mt-4">
        {state.status === "error" ? <p className="text-sm text-body">{state.message}</p> : null}
        {state.status === "ok" ? (
          <pre
            tabIndex={0}
            className="overflow-x-auto rounded-xl border border-hairline bg-canvas p-4 font-mono text-[13px] text-ink"
          >
            <code>{JSON.stringify(state.record, null, 2)}</code>
          </pre>
        ) : null}
      </div>
    </form>
  );
}
