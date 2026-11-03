import { Button } from "@paypal-seed/ui/components/button";
import { Wallet } from "lucide-react";

/**
 * Shown only when the backend says it holds no payout recipient (the member never signed in with PayPal). Drawn for
 * `false` alone: an absent field means identity could not answer, and then no warning beats a wrong one (ADR 0027).
 * The link is a plain anchor to the route handler that starts the PayPal sign-in, as on the login page.
 */
export function PayoutRecipientBanner() {
  return (
    <aside
      role="note"
      aria-labelledby="payout-recipient-title"
      className="flex flex-col gap-4 rounded-2xl border border-hairline bg-canvas-soft p-5 sm:flex-row sm:items-center sm:justify-between"
    >
      <div className="flex gap-3">
        <Wallet aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-muted-ink" />
        <div className="flex flex-col gap-1">
          <h2 id="payout-recipient-title" className="text-title-sm font-medium text-ink">
            One small step before money can reach you
          </h2>
          <p className="text-body text-pretty">Log in with PayPal again to add the account we pay out to.</p>
        </div>
      </div>
      <Button asChild className="h-11 self-start sm:self-auto">
        <a href="/api/auth/paypal/start">Log in with PayPal</a>
      </Button>
    </aside>
  );
}
