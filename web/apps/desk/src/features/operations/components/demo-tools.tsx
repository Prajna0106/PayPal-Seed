"use client";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@paypal-seed/ui/components/alert-dialog";
import { Badge } from "@paypal-seed/ui/components/badge";
import { Button } from "@paypal-seed/ui/components/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { Radar, Timer } from "lucide-react";
import { type ReactNode, startTransition, useActionState } from "react";
import type { ActionResult } from "@/features/hardship/actions";
import { runScanOnly, runSweepOnly } from "../actions";
import { Result, useResultToast } from "./operation-card";

const initial: ActionResult = { status: "idle" };

type NoInputAction = (previous: ActionResult) => Promise<ActionResult>;

function DemoButton({
  action,
  label,
  busy,
  confirmTitle,
  confirmBody,
  icon,
}: {
  action: NoInputAction;
  label: string;
  busy: string;
  confirmTitle: string;
  confirmBody: string;
  icon: ReactNode;
}) {
  const [state, run, pending] = useActionState(action, initial);
  useResultToast(state);
  return (
    <div className="flex flex-col gap-2">
      <AlertDialog>
        <AlertDialogTrigger asChild>
          <Button variant="outline" disabled={pending} className="self-start [&_svg]:size-4">
            {icon}
            {pending ? busy : label}
          </Button>
        </AlertDialogTrigger>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{confirmTitle}</AlertDialogTitle>
            <AlertDialogDescription>{confirmBody}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Not now</AlertDialogCancel>
            <AlertDialogAction
              onClick={() =>
                startTransition(() => {
                  run();
                })
              }
            >
              {label}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      <Result state={state} />
    </div>
  );
}

/**
 * Demo tools: the hardship scan and the timer sweep as two separate steps, so a presenter can show a case opening
 * first and the timers firing after. The service token stays on the server; these only call Server Actions.
 */
export function DemoTools() {
  return (
    <Card className="border-dashed">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          Hardship demo tools
          <Badge variant="outline">Demo tools</Badge>
        </CardTitle>
        <CardDescription>
          The worker runs both of these on its own schedule. Run them here to show the safety net step by step.
          Running either twice is safe.
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-6 md:grid-cols-2">
        <DemoButton
          action={runScanOnly}
          label="Run hardship scan"
          busy="Scanning…"
          icon={<Radar aria-hidden="true" />}
          confirmTitle="Run the hardship scan?"
          confirmBody="Seed checks every loan with a payment due in the next 10 days or in grace, and opens a case for each member who is likely to miss. Nothing is sent to a member by this step."
        />
        <DemoButton
          action={runSweepOnly}
          label="Run timer sweep"
          busy="Sweeping…"
          icon={<Timer aria-hidden="true" />}
          confirmTitle="Run the timer sweep?"
          confirmBody="Seed fires every hardship case timer that is due now: offer reminders and expiry, holds, and approval deadline warnings. Each timer fires once."
        />
      </CardContent>
    </Card>
  );
}
