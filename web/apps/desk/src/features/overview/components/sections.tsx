import { isProblem, problemMessage } from "@paypal-seed/api-client/problem";
import { AgentCard } from "@paypal-seed/ui/brand/agent";
import { BusinessDate } from "@paypal-seed/ui/brand/business-date";
import { Kpi } from "@paypal-seed/ui/brand/kpi";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@paypal-seed/ui/components/card";
import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import {
  AlertTriangle,
  Bot,
  ChevronRight,
  FolderOpen,
  UserCheck,
  Users,
} from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import type { ReactNode } from "react";
import { unstable_rethrow } from "next/navigation";
import { CASE_STATUSES, RISK_LEVELS } from "@/features/hardship/model";
import {
  agentState,
  casesByStatus,
  deskBriefing,
  deskKpis,
  needsAttention,
  recentCobRuns,
} from "../server/queries";
import { CobRunsTable } from "./cob-runs-table";
import { StatusChart } from "./status-chart";

const ratio = new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

type Loaded<T> = { ok: true; value: T } | { ok: false; message: string };

/** Data is loaded inside try/catch and rendered outside it: a render error must reach the error boundary. */
async function load<T>(fetcher: () => Promise<T>): Promise<Loaded<T>> {
  try {
    return { ok: true, value: await fetcher() };
  } catch (error) {
    unstable_rethrow(error);
    return {
      ok: false,
      message: isProblem(error)
        ? problemMessage(error)
        : "We could not reach the Seed backend.",
    };
  }
}

function Unavailable({ message }: { message: string }) {
  return (
    <p
      role="alert"
      className="flex items-center gap-2 text-sm text-danger [&_svg]:size-4"
    >
      <AlertTriangle aria-hidden="true" />
      {message}
    </p>
  );
}

const MODE_TEXT = {
  LIVE: {
    label: "Live",
    hint: "Guardian Agent text reaches members after the checks.",
  },
  SHADOW: { label: "Shadow", hint: "Guardian Agent runs; members get templates." },
  TEMPLATES_ONLY: {
    label: "Templates only",
    hint: "No model calls. Templates go out.",
  },
} as const;

export async function KpiSection() {
  const loaded = await load(() => Promise.all([deskKpis(), agentState()]));
  if (!loaded.ok) {
    return (
      <Card>
        <CardContent className="py-6">
          <Unavailable message={loaded.message} />
        </CardContent>
      </Card>
    );
  }
  const [kpis, agent] = loaded.value;
  const mode = MODE_TEXT[agent.mode ?? "TEMPLATES_ONLY"];
  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
      <Kpi
        label="Open cases"
        value={kpis.open}
        icon={<FolderOpen />}
        hint="Not yet applied, declined or expired"
      />
      <Kpi
        label="Waiting for a maker"
        value={kpis.waitingForMaker}
        icon={<UserCheck />}
        hint={
          kpis.pendingSampled
            ? "From the oldest 30 approvals"
            : "First officer to decide"
        }
      />
      <Kpi
        label="Waiting for a checker"
        value={kpis.waitingForChecker}
        icon={<Users />}
        hint="Second, different officer"
      />
      <Kpi
        label="Apply failed"
        value={kpis.failed}
        icon={<AlertTriangle />}
        hint={
          kpis.failed
            ? "Open the case to retry"
            : "Every approved plan was applied"
        }
      />
      <Kpi
        label="Guardian Agent"
        value={<span className="text-3xl">{mode.label}</span>}
        icon={<Bot />}
        hint={mode.hint}
      />
    </div>
  );
}

export function KpiSkeleton() {
  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5" aria-busy="true">
      {Array.from({ length: 5 }, (_, i) => (
        <Skeleton key={i} className="h-33 rounded-xl" />
      ))}
    </div>
  );
}

function Panel({
  title,
  description,
  children,
  action,
}: {
  title: string;
  description?: string;
  children: ReactNode;
  action?: ReactNode;
}) {
  return (
    <Card className="min-w-0">
      <CardHeader>
        <div className="flex items-start justify-between gap-3">
          <div className="flex flex-col gap-1.5">
            <CardTitle>{title}</CardTitle>
            {description ? (
              <CardDescription>{description}</CardDescription>
            ) : null}
          </div>
          {action}
        </div>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}

export async function StatusSection() {
  const loaded = await load(casesByStatus);
  let content: ReactNode;
  if (!loaded.ok) {
    content = <Unavailable message={loaded.message} />;
  } else if (loaded.value.length === 0) {
    content = (
      <p className="text-sm text-muted-ink">
        No cases yet. The daily scan opens one when a member is likely to miss a
        payment.
      </p>
    );
  } else {
    content = (
      <StatusChart
        data={loaded.value.map((c) => ({
          ...c,
          label: CASE_STATUSES[c.status].label,
          tone: CASE_STATUSES[c.status].tone,
        }))}
      />
    );
  }
  return (
    <Panel title="Cases by status" description="The newest 200 cases">
      {content}
    </Panel>
  );
}

export async function AttentionSection() {
  const loaded = await load(needsAttention);
  let content: ReactNode;
  if (!loaded.ok) {
    content = <Unavailable message={loaded.message} />;
  } else if (loaded.value.length === 0) {
    content = (
      <p className="text-sm text-muted-ink">
        Nothing is waiting on an officer.
      </p>
    );
  } else {
    content = (
      <ul className="flex flex-col divide-y divide-hairline-soft">
        {loaded.value.map((row) => {
          const level = row.level ? RISK_LEVELS[row.level] : undefined;
          const status = row.status ? CASE_STATUSES[row.status] : undefined;
          return (
            <li key={row.caseId}>
              <Link
                href={`/hardship/${row.caseId}` as Route}
                className="group flex items-center gap-3 rounded-lg py-3 outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <div className="flex min-w-0 flex-1 flex-col gap-1">
                  <p className="text-sm font-medium text-ink">
                    {row.targetAmountCents !== undefined
                      ? formatMoney(row.targetAmountCents)
                      : "—"}{" "}
                    due{" "}
                    {row.targetDueDate ? (
                      <BusinessDate
                        value={row.targetDueDate}
                        dateStyle="short"
                      />
                    ) : (
                      "—"
                    )}
                    {row.chosenOption ? (
                      <span className="text-muted-ink">
                        {" "}
                        · chose {row.chosenOption}
                      </span>
                    ) : null}
                  </p>
                  <div className="flex flex-wrap gap-1.5">
                    {status ? (
                      <StatusPill tone={status.tone}>{status.label}</StatusPill>
                    ) : null}
                    {level ? (
                      <StatusPill tone={level.tone} dot={false}>
                        {level.label}
                      </StatusPill>
                    ) : null}
                  </div>
                </div>
                <ChevronRight
                  aria-hidden="true"
                  className="size-4 text-muted-ink transition-transform group-hover:translate-x-0.5"
                />
              </Link>
            </li>
          );
        })}
      </ul>
    );
  }
  return (
    <Panel
      title="Needs an officer"
      description="Oldest first"
      action={
        <Link
          href="/hardship?status=PENDING_APPROVAL"
          className="text-sm text-ink underline-offset-4 hover:underline"
        >
          View all
        </Link>
      }
    >
      {content}
    </Panel>
  );
}

export async function CobSection() {
  const loaded = await load(recentCobRuns);
  const content = loaded.ok ? (
    <CobRunsTable runs={loaded.value} />
  ) : (
    <Unavailable message={loaded.message} />
  );
  return (
    <Panel
      title="Close of business"
      description="Daily aging, timers and late buckets, newest first"
    >
      {content}
    </Panel>
  );
}

function BriefingList({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) {
    return null;
  }
  return (
    <div className="flex flex-col gap-2">
      <h3 className="text-sm font-medium text-ink">{title}</h3>
      <ul className="flex list-disc flex-col gap-1.5 pl-5 text-[15px] leading-relaxed text-body">
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </div>
  );
}

/**
 * Morning briefing. The wording is labelled AI-written only when the backend says the model wrote it (it passes the
 * grounding check before it is returned); otherwise it is the template. Figures come from structured fields.
 * A failed call hides the card: the overview works without it.
 */
export async function BriefingSection() {
  const loaded = await load(deskBriefing);
  if (!loaded.ok) {
    return null;
  }
  const b = loaded.value;
  const metrics: { label: string; value: ReactNode }[] = [
    { label: "Active loans", value: b.activeLoansCount },
    { label: "Outstanding", value: formatMoney(b.totalOutstandingCents) },
    { label: "Healthy / at risk", value: `${b.healthyLoansCount} / ${b.atRiskLoansCount}` },
    { label: "Cases to review", value: b.casesPendingReviewCount },
    { label: "Oldest case", value: `${b.oldestCaseHours} h` },
    { label: "SLA breaches", value: b.slaBreachesCount },
    { label: "Reserve", value: formatMoney(b.reserveBalanceCents) },
    {
      label: "Reserve coverage",
      value: b.reserveCoverageRatio === null ? "No cover needed" : `${ratio.format(b.reserveCoverageRatio)}x`,
    },
  ];
  return (
    <Card className="min-w-0">
      <CardHeader>
        <CardTitle>Morning briefing</CardTitle>
        <CardDescription>
          Portfolio and queue as of <BusinessDate value={b.businessDate} dateStyle="long" />. Figures below come from
          code; the violet block is the Desk Analyst Agent&apos;s wording.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <dl className="grid grid-cols-2 gap-x-6 gap-y-4 sm:grid-cols-4">
          {metrics.map((m) => (
            <div key={m.label} className="flex flex-col gap-0.5">
              <dt className="text-xs text-muted-ink">{m.label}</dt>
              <dd className="text-lg font-medium text-ink">{m.value}</dd>
            </div>
          ))}
        </dl>
        <AgentCard
          agent="analyst"
          state={b.modelGenerated ? "ai" : "template"}
          footer={
            b.modelGenerated
              ? "Figures come from code and were checked against this wording."
              : "Template wording over the figures above: no agent wrote this."
          }
        >
          <div className="flex flex-col gap-6">
            <p className="whitespace-pre-line text-[15px] leading-relaxed text-body">{b.executiveSummary}</p>
            <div className="grid gap-6 md:grid-cols-2">
              <BriefingList title="Queue priorities" items={b.queuePriorities} />
              <BriefingList title="Risk observations" items={b.riskObservations} />
            </div>
            {b.recommendedStaffingFocus ? (
              <div className="flex flex-col gap-1">
                <h3 className="text-sm font-medium text-ink">Staffing focus</h3>
                <p className="text-[15px] leading-relaxed text-body">{b.recommendedStaffingFocus}</p>
              </div>
            ) : null}
          </div>
        </AgentCard>
      </CardContent>
    </Card>
  );
}

export function PanelSkeleton({ height = "h-64" }: { height?: string }) {
  return (
    <Skeleton className={`${height} w-full rounded-xl`} aria-busy="true" />
  );
}
