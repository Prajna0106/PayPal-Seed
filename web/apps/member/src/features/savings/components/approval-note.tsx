import { Button } from "@paypal-seed/ui/components/button";
import { formatDateTime } from "@paypal-seed/ui/lib/format";
import { ExternalLink } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { type Goal, safeApprovalUrl } from "../model";

/**
 * A goal that waits for the member to agree at PayPal. With a link from the backend the member goes straight back to
 * PayPal; without one (or after it expired) the safe way is to start the goal again.
 */
export function ApprovalNote({ goal, className }: { goal: Goal; className?: string }) {
  const link = safeApprovalUrl(goal.approvalUrl);
  const restart = `/app/savings/new?name=${encodeURIComponent(goal.name)}` as Route;

  return (
    <div className={["flex flex-col gap-3 rounded-xl bg-warning-soft p-4", className].filter(Boolean).join(" ")}>
      <p className="text-sm text-ink">
        {link
          ? "Your goal starts as soon as you agree to the monthly amount at PayPal. Nothing has been taken yet."
          : "We could not find your PayPal link. Nothing has been taken. Start the goal again and we will take you to PayPal."}
      </p>
      {link ? (
        <>
          <Button asChild className="self-start">
            <a href={link}>
              Finish approving at PayPal
              <ExternalLink aria-hidden="true" />
            </a>
          </Button>
          {goal.approvalExpiresAt ? (
            <p className="text-xs text-body">The link works until {formatDateTime(goal.approvalExpiresAt)}.</p>
          ) : null}
        </>
      ) : (
        <Button asChild variant="outline" className="self-start">
          <Link href={restart}>Start again</Link>
        </Button>
      )}
    </div>
  );
}
