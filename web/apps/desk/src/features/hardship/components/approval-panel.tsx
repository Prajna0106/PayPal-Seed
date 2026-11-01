import { StatusPill, type StatusTone } from "@paypal-seed/ui/brand/status-pill";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { formatDateTime, formatRelative } from "@paypal-seed/ui/lib/format";
import { CircleCheck, CircleX, Clock, UserCheck } from "lucide-react";
import type { ReactNode } from "react";
import { REASON_LABELS, TIER_NAMES } from "../model";
import type { Approval, ApprovalStage } from "../server/queries";

const STATUS_TONES: Record<string, StatusTone> = {
  OPEN: "warning",
  APPROVED: "success",
  REJECTED: "danger",
  EXPIRED: "neutral",
  VOIDED: "neutral",
};

const STAGE_TEXT: Record<ApprovalStage, string> = {
  WAITING_FOR_MAKER: "Waiting for the first officer (maker)",
  WAITING_FOR_CHECKER: "Waiting for a second, different officer (checker)",
  DECIDED: "Decided",
  NONE: "No approval needed yet",
};

const ROLE_TEXT: Record<string, string> = { POLICY: "Policy", MAKER: "Maker", CHECKER: "Checker" };

/** The approval request: who has decided, who is next, and the service-level clock. */
export function ApprovalPanel({ approval, stage, children }: { approval: Approval | null; stage: ApprovalStage; children?: ReactNode }) {
  if (!approval) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Approval</CardTitle>
          <CardDescription>Opens when the member chooses an option. Option A is approved by policy; B and C need two officers.</CardDescription>
        </CardHeader>
      </Card>
    );
  }
  const decisions = approval.decisions ?? [];
  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between gap-2">
          <CardTitle>Approval</CardTitle>
          <StatusPill tone={STATUS_TONES[approval.status ?? ""] ?? "neutral"}>{approval.status}</StatusPill>
        </div>
        <CardDescription>{STAGE_TEXT[stage]}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-5">
        <dl className="grid grid-cols-2 gap-3 text-sm">
          <div className="flex flex-col gap-0.5">
            <dt className="text-xs text-muted-ink">Tier</dt>
            <dd className="font-medium text-ink">{TIER_NAMES[approval.tier ?? ""] ?? approval.tier}</dd>
          </div>
          <div className="flex flex-col gap-0.5">
            <dt className="text-xs text-muted-ink">Second officer</dt>
            <dd className="font-medium text-ink">{approval.checkerRequired === false ? "Not required" : "Required"}</dd>
          </div>
          {approval.slaDueAt ? (
            <div className="col-span-2 flex flex-col gap-0.5">
              <dt className="flex items-center gap-1 text-xs text-muted-ink [&_svg]:size-3.5">
                <Clock aria-hidden="true" />
                Decide by
              </dt>
              <dd className="font-medium text-ink">
                {formatDateTime(approval.slaDueAt)}{" "}
                {approval.status === "OPEN" ? (
                  <span className="font-normal text-muted-ink">({formatRelative(approval.slaDueAt)})</span>
                ) : null}
              </dd>
            </div>
          ) : null}
        </dl>

        <ol className="flex flex-col gap-3" aria-label="Decisions">
          {decisions.length === 0 ? (
            <li className="flex items-center gap-2 text-sm text-muted-ink [&_svg]:size-4">
              <UserCheck aria-hidden="true" />
              No decisions yet.
            </li>
          ) : (
            decisions.map((d, index) => (
              <li key={`${d.role}-${index}`} className="flex items-start gap-3 text-sm">
                <span className={d.decision === "APPROVE" ? "text-success [&_svg]:size-5" : "text-danger [&_svg]:size-5"}>
                  {d.decision === "APPROVE" ? <CircleCheck aria-hidden="true" /> : <CircleX aria-hidden="true" />}
                </span>
                <div className="flex flex-col">
                  <p className="text-ink">
                    <span className="font-medium">{ROLE_TEXT[d.role ?? ""] ?? d.role}</span>{" "}
                    {d.decision === "APPROVE" ? "approved" : "rejected"}
                    {d.reasonCode ? <span className="text-body"> · {REASON_LABELS[d.reasonCode] ?? d.reasonCode}</span> : null}
                  </p>
                  <p className="text-xs text-muted-ink">
                    {d.staffRef ? <span className="font-mono">{d.staffRef}</span> : null}
                    {d.decidedAt ? <> · {formatDateTime(d.decidedAt)}</> : null}
                  </p>
                </div>
              </li>
            ))
          )}
        </ol>
        {children}
      </CardContent>
    </Card>
  );
}
