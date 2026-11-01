import { isProblem, problemMessage } from "@paypal-seed/api-client/problem";
import type { KillSwitchState } from "@paypal-seed/api-client/types";
import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { StatusPill, type StatusTone } from "@paypal-seed/ui/brand/status-pill";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { formatDateTime, formatRelative } from "@paypal-seed/ui/lib/format";
import { ShieldAlert } from "lucide-react";
import type { Metadata } from "next";
import { KillSwitchForm } from "@/features/agents/components/kill-switch-form";
import { agentState } from "@/features/overview/server/queries";
import { hasRole, requireSession } from "@/server/session";

export const metadata: Metadata = { title: "Agents" };

const MODE_TONES: Record<string, StatusTone> = { LIVE: "success", SHADOW: "info", TEMPLATES_ONLY: "warning" };

const TRIPPED_BY: Record<string, string> = {
  OFFICER: "Set by staff",
  AUTO_VALIDATOR_REJECTIONS: "Tripped automatically: too many proposals rejected",
  AUTO_GROUNDING_FAILURES: "Tripped automatically: grounding failures",
  AUTO_BUDGET: "Tripped automatically: daily budget spent",
  AUTO_INJECTION_CANARY: "Tripped automatically: injection canary",
};

const CIRCUIT_TONES: Record<string, StatusTone> = { CLOSED: "success", HALF_OPEN: "warning", OPEN: "danger" };

export default async function AgentsPage() {
  const session = await requireSession();
  let state: KillSwitchState;
  try {
    state = await agentState();
  } catch (error) {
    return (
      <>
        <PageHeader eyebrow="Platform" title="Agents" />
        <EmptyState
          icon={<ShieldAlert />}
          title="Agent status is unavailable"
          description={isProblem(error) ? problemMessage(error) : "We could not reach the Seed backend."}
        />
      </>
    );
  }
  const mode = state.mode ?? "TEMPLATES_ONLY";
  const circuits = Object.entries(state.circuits ?? {});
  return (
    <>
      <PageHeader
        eyebrow="Platform"
        title="Agents"
        description="The Guardian proposes; code checks every figure; people decide. The kill switch drops it to templates at once, and the safety net keeps working."
      />
      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
        <Card>
          <CardHeader>
            <CardTitle>Kill switch</CardTitle>
            <CardDescription>Every change is recorded with your name and reason.</CardDescription>
          </CardHeader>
          <CardContent>
            <KillSwitchForm key={mode} current={mode} isAdmin={hasRole(session, "ADMIN")} />
          </CardContent>
        </Card>
        <div className="flex flex-col gap-6">
          <Card>
            <CardHeader>
              <CardTitle>Guardian now</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-3 text-sm">
              <StatusPill tone={MODE_TONES[mode] ?? "neutral"} className="self-start">
                {mode === "TEMPLATES_ONLY" ? "Templates only" : mode === "SHADOW" ? "Shadow" : "Live"}
              </StatusPill>
              {state.trippedBy ? <p className="text-body">{TRIPPED_BY[state.trippedBy] ?? state.trippedBy}</p> : null}
              {state.reason ? <p className="text-body">“{state.reason}”</p> : null}
              {state.since ? (
                <p className="text-muted-ink">
                  Since {formatDateTime(state.since)} ({formatRelative(state.since)})
                </p>
              ) : null}
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Model circuits</CardTitle>
              <CardDescription>A model that keeps failing is skipped for a minute, then tried again.</CardDescription>
            </CardHeader>
            <CardContent>
              {circuits.length === 0 ? (
                <p className="text-sm text-muted-ink">No model calls yet in this process.</p>
              ) : (
                <ul className="flex flex-col gap-2 text-sm">
                  {circuits.map(([model, circuit]) => (
                    <li key={model} className="flex items-center justify-between gap-3">
                      <span className="truncate font-mono text-xs text-ink">{model}</span>
                      <StatusPill tone={CIRCUIT_TONES[circuit] ?? "neutral"}>{circuit}</StatusPill>
                    </li>
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>
        </div>
      </div>
    </>
  );
}
