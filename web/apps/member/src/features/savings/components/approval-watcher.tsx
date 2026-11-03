"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { CheckCircle2, Loader2 } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { syncGoal } from "../actions";
import type { GoalStatus } from "../model";

const EVERY_MS = 3000;
const ATTEMPTS = 20; // about a minute

/**
 * PayPal tells the backend when the member agreed, and the backend activates the goal from that message. This page
 * asks the server for the goal again every few seconds (the browser never talks to the backend itself) and says what
 * it finds, up to about a minute.
 */
export function ApprovalWatcher({ goalId, goalName, status }: { goalId: string; goalName: string; status: GoalStatus }) {
  const router = useRouter();
  const [gaveUp, setGaveUp] = useState(false);
  // Bumped by "Check again" so the polling starts a fresh minute.
  const [round, setRound] = useState(0);
  const waiting = status === "PENDING_APPROVAL";

  useEffect(() => {
    if (!waiting) {
      return;
    }
    let attempts = 0;
    // The webhook may never reach this machine, so each round also asks the backend to read PayPal directly.
    const check = () => void syncGoal(goalId).finally(() => router.refresh());
    check();
    const timer = window.setInterval(() => {
      attempts += 1;
      if (attempts >= ATTEMPTS) {
        window.clearInterval(timer);
        setGaveUp(true);
        return;
      }
      check();
    }, EVERY_MS);
    return () => window.clearInterval(timer);
  }, [waiting, router, round, goalId]);

  const goalHref = `/app/savings/${goalId}` as Route;

  return (
    <div role="status" aria-live="polite" className="flex flex-col items-start gap-4 rounded-2xl border border-hairline bg-surface p-6">
      {status === "ACTIVE" || status === "COMPLETED" ? (
        <>
          <p className="inline-flex items-center gap-2 text-base font-medium text-ink">
            <CheckCircle2 className="size-5 text-success" aria-hidden="true" />
            You are all set. {goalName} is saving.
          </p>
          <p className="text-sm text-body">PayPal confirmed your plan. Your first deposit arrives on PayPal&apos;s schedule.</p>
          <Button asChild>
            <Link href={goalHref}>See your goal</Link>
          </Button>
        </>
      ) : waiting && !gaveUp ? (
        <>
          <p className="inline-flex items-center gap-2 text-base font-medium text-ink">
            <Loader2 className="size-5 animate-spin motion-reduce:animate-none" aria-hidden="true" />
            Waiting for PayPal to confirm
          </p>
          <p className="text-sm text-body">This usually takes a few seconds. You can keep this page open.</p>
        </>
      ) : waiting ? (
        <>
          <p className="text-base font-medium text-ink">PayPal is taking a little longer</p>
          <p className="text-sm text-body">
            You can leave this page. Your goal starts by itself when PayPal confirms, and it shows on your savings page. If you did
            not finish at PayPal, you can go back and finish from your goals.
          </p>
          <div className="flex flex-wrap gap-2">
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                setGaveUp(false);
                setRound((value) => value + 1);
                router.refresh();
              }}
            >
              Check again
            </Button>
            <Button asChild variant="ghost">
              <Link href={"/app/savings" as Route}>Back to your goals</Link>
            </Button>
          </div>
        </>
      ) : (
        <>
          <p className="text-base font-medium text-ink">This goal is not saving yet</p>
          <p className="text-sm text-body">
            It was not confirmed at PayPal, so nothing was set up and nothing was taken. You can start again whenever you like.
          </p>
          <Button asChild>
            <Link href={"/app/savings/new" as Route}>Start a goal</Link>
          </Button>
        </>
      )}
    </div>
  );
}
