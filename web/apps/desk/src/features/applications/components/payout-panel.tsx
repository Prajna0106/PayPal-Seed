"use client";

import { Alert, AlertDescription, AlertTitle } from "@paypal-seed/ui/components/alert";
import { Button } from "@paypal-seed/ui/components/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { CircleCheck, LoaderCircle, TriangleAlert } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { disburseApplication } from "../actions";
import { ActionDialog } from "./action-dialog";

const POLL_MS = 5000;
/** About three minutes: PayPal answers a payout in seconds; past this the officer can refresh by hand. */
const MAX_POLLS = 36;

/** While a payout is running the page asks the server again every few seconds; it stops when the tab is hidden. */
function PayoutWatcher() {
  const router = useRouter();
  const [stopped, setStopped] = useState(false);

  useEffect(() => {
    let polls = 0;
    const id = window.setInterval(() => {
      if (document.hidden) {
        return;
      }
      polls += 1;
      if (polls > MAX_POLLS) {
        window.clearInterval(id);
        setStopped(true);
        return;
      }
      router.refresh();
    }, POLL_MS);
    return () => window.clearInterval(id);
  }, [router]);

  return (
    <div aria-live="polite" className="flex items-start gap-3 text-sm text-body">
      <LoaderCircle aria-hidden="true" className="mt-0.5 size-4 shrink-0 animate-spin motion-reduce:animate-none" />
      <div className="flex flex-col gap-2">
        <p>
          {stopped
            ? "PayPal has not confirmed yet. This can take a few minutes."
            : "Waiting for PayPal to confirm the payout. This page updates by itself."}
        </p>
        {stopped ? (
          <Button variant="outline" size="sm" className="self-start" onClick={() => router.refresh()}>
            Check again
          </Button>
        ) : null}
      </div>
    </div>
  );
}

export interface PayoutPanelProps {
  applicationId: string;
  status: string;
  loanId: string | null;
  /** Plain-language reason from the server (`explainPayoutIssue`); the raw PayPal reason never reaches the browser. */
  payoutIssueText: string | null;
  summary: string;
  disburseKey: string;
}

/** The last step: pay out, watch the payout, or read why it was refused and try again. */
export function PayoutPanel({ applicationId, status, loanId, payoutIssueText, summary, disburseKey }: PayoutPanelProps) {
  if (status !== "APPROVED" && status !== "DISBURSING" && status !== "DISBURSED") {
    return null;
  }
  return (
    <Card>
      <CardHeader>
        <CardTitle>Payout</CardTitle>
        <CardDescription>
          {status === "APPROVED"
            ? "Two officers approved this. Paying out sends the money through PayPal."
            : status === "DISBURSING"
              ? "The payout is with PayPal."
              : "The member has been paid."}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {status === "DISBURSING" ? <PayoutWatcher /> : null}

        {status === "DISBURSED" ? (
          <div className="flex flex-col gap-3 text-sm text-body">
            <p className="flex items-center gap-2 text-success [&_svg]:size-4">
              <CircleCheck aria-hidden="true" />
              Paid out. The loan and its schedule exist.
            </p>
            {loanId ? (
              <Button asChild variant="outline" className="h-11 self-start">
                <Link href={`/loans/${loanId}` as Route}>Open the loan</Link>
              </Button>
            ) : null}
          </div>
        ) : null}

        {status === "APPROVED" ? (
          <>
            {payoutIssueText ? (
              <Alert variant="destructive">
                <TriangleAlert aria-hidden="true" />
                <AlertTitle>The last payout did not go through</AlertTitle>
                <AlertDescription>{payoutIssueText}</AlertDescription>
              </Alert>
            ) : null}
            <ActionDialog
              triggerLabel={payoutIssueText ? "Try the payout again" : "Pay out through PayPal"}
              title={payoutIssueText ? "Try the payout again?" : "Pay out this loan?"}
              description={
                <>
                  <p>{summary}.</p>
                  <p className="mt-2">
                    This sends the money to the member&apos;s PayPal account. The loan and its repayment schedule are
                    created only once PayPal confirms the payout.
                  </p>
                </>
              }
              submitLabel="Pay out"
              pendingLabel="Starting payout…"
              action={disburseApplication}
              hidden={{ applicationId, key: disburseKey }}
            />
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}
