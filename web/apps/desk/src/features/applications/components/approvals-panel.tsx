import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { CircleCheck, Hourglass } from "lucide-react";
import type { ReactNode } from "react";
import { APPLICATION_STATUSES, type LoanApplication } from "../model";

function Signature({ role, name, mine }: { role: string; name: string | undefined; mine: boolean }) {
  return (
    <li className="flex items-start gap-3 text-sm">
      <span className={name ? "text-success [&_svg]:size-5" : "text-muted-ink [&_svg]:size-5"}>
        {name ? <CircleCheck aria-hidden="true" /> : <Hourglass aria-hidden="true" />}
      </span>
      <div className="flex flex-col">
        <p className="font-medium text-ink">{role}</p>
        <p className="text-xs text-muted-ink">
          {name ? `Signed by ${name}${mine ? " (you)" : ""}` : "Not signed yet"}
        </p>
      </div>
    </li>
  );
}

function stillNeeded(application: LoanApplication): string {
  switch (application.status) {
    case "SUBMITTED":
      return "Two different officers must approve. Nobody has signed yet.";
    case "PENDING_SECOND_APPROVAL":
      return "One signature is still needed, from a different officer than the first.";
    case "APPROVED":
      return "Both signatures are in. The next step is the payout.";
    case "DISBURSING":
    case "DISBURSED":
      return "Both signatures are in.";
    case "REJECTED":
      return "An officer rejected this application.";
    case "CANCELLED_COOLING_OFF":
      return "The member cancelled it before any approval was needed.";
  }
}

export interface ApprovalsPanelProps {
  application: LoanApplication;
  staffName: (staffId: string) => string;
  currentStaffId: string;
  children?: ReactNode;
}

/** Maker and checker: who has signed, who is still needed, and (as children) what this officer can do about it. */
export function ApprovalsPanel({ application, staffName, currentStaffId, children }: ApprovalsPanelProps) {
  const status = APPLICATION_STATUSES[application.status];
  const first = application.firstApproverId ?? undefined;
  const second = application.secondApproverId ?? undefined;
  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between gap-2">
          <CardTitle>Approvals</CardTitle>
          <StatusPill tone={status.tone}>{status.label}</StatusPill>
        </div>
        <CardDescription>{stillNeeded(application)}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        <ol className="flex flex-col gap-4" aria-label="Signatures">
          <Signature role="First approval (maker)" name={first ? staffName(first) : undefined} mine={first === currentStaffId} />
          <Signature
            role="Second approval (checker)"
            name={second ? staffName(second) : undefined}
            mine={second === currentStaffId}
          />
        </ol>
        {children}
      </CardContent>
    </Card>
  );
}
