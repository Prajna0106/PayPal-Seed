"use client";

import { Alert, AlertDescription, AlertTitle } from "@paypal-seed/ui/components/alert";
import { Button } from "@paypal-seed/ui/components/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { CircleCheck, LoaderCircle, TriangleAlert } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState, useTransition } from "react";
import { disburseApplication, syncApplicationPayout } from "../actions";
import { ActionDialog } from "./action-dialog";

const POLL_MS = 5000;
/** About two minutes: PayPal answers a payout in seconds; past this the officer checks by hand. */
const MAX_POLLS = 24;

/**
 * While a payout is running the panel asks the backend to read it from PayPal every few seconds (a machine PayPal cannot
 * call never gets the webhook), and the action refreshes the page with the answer. It stops when the tab is hidden and
 * after about two minutes; "Check again" does the same read by hand.
 */
function PayoutWatcher({ applicationId }: { applicationId: string }) {
  const [stopped, setStopped] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [checking, startChecking] = useTransition();
  const inFlight = useRef(false);

  const check = useCallback(() => {
    if (inFlight.current) {
      return;
    }
    inFlight.current = true;
    startChecking(async () => {
      try {
        const result = await syncApplicationPayout(applicationId);
        setProblem(result.status === "error" ? result.message : null);
      } finally {
        inFlight.current = false;
      }
    });
  }, [applicationId]);

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
      check();
    }, POLL_MS);
    return () => window.clearInterval(id);
  }, [check]);

  return (
    <div aria-live="polite" className="flex items-start gap-3 text-sm text-body">
      <LoaderCircle aria-hidden="true" className="mt-0.5 size-4 shrink-0 animate-spin motion-reduce:animate-none" />
      <div className="flex flex-col gap-2">
        <p>
          {stopped
            ? "PayPal has not confirmed yet. This can take a few minutes."
            : "Waiting for PayPal to confirm the payout. This page checks by itself."}
        </p>
        {problem ? <p className="text-destructive">{problem}</p> : null}
        {stopped ? (
          <Button variant="outline" size="sm" className="h-11 self-start" disabled={checking} onClick={check}>
            {checking ? "Checking…" : "Check again"}
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
  /** The payout came back after the loan was registered, so the loan was voided (ADR 0024). */
  payoutReturned?: boolean;
  summary: string;
  disburseKey: string;
}

/** The last step: pay out, watch the payout, or read why it was refused and try again. */
export function PayoutPanel({ applicationId, status, loanId, payoutIssueText, payoutReturned = false, summary, disburseKey }: PayoutPanelProps) {
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
        {status === "DISBURSING" ? <PayoutWatcher applicationId={applicationId} /> : null}

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
                <AlertTitle>{payoutReturned ? "The payout came back" : "The last payout did not go through"}</AlertTitle>
                <AlertDescription>{payoutIssueText}</AlertDescription>
              </Alert>
            ) : null}
            <ActionDialog
              triggerLabel={payoutIssueText ? (payoutReturned ? "Send the payout again" : "Try the payout again") : "Pay out through PayPal"}
              title={payoutIssueText ? (payoutReturned ? "Send the payout again?" : "Try the payout again?") : "Pay out this loan?"}
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
