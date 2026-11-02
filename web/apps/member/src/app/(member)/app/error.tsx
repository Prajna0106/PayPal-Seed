"use client";

import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { Button } from "@paypal-seed/ui/components/button";

/** A page inside the member area failed as a whole. The top bar stays; the digest lets support find the server log. */
export default function MemberError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <EmptyState
      title="We could not load this page"
      description={
        <>
          Nothing has changed on your account. Please try again. If it keeps happening, quote this reference: <code>{error.digest ?? "none"}</code>
        </>
      }
      action={<Button onClick={reset}>Try again</Button>}
    />
  );
}
