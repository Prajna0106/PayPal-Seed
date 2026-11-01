"use client";

import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { Button } from "@paypal-seed/ui/components/button";

/** Last-resort boundary. Shows no internals: the digest lets support find the server log. */
export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <main className="mx-auto flex min-h-dvh max-w-xl items-center px-6">
      <EmptyState
        className="w-full"
        title="Something went wrong"
        description={
          <>
            Please try again. If it keeps happening, quote this reference: <code>{error.digest ?? "none"}</code>
          </>
        }
        action={<Button onClick={reset}>Try again</Button>}
      />
    </main>
  );
}
