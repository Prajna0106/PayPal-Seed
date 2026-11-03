import { isProblem, problemMessage } from "@paypal-seed/api-client/problem";
import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { Kpi } from "@paypal-seed/ui/brand/kpi";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from "@paypal-seed/ui/components/table";
import { formatDateTime } from "@paypal-seed/ui/lib/format";
import { AlertTriangle, CheckCheck } from "lucide-react";
import { KIND_NAMES, ROLE_NAMES, SLA_LABELS, SLA_TONES, workloadBars, workloadRows } from "../model";
import { type Workload, workload } from "../server/queries";
import { WorkloadChartLoader } from "./workload-chart-loader";

type Loaded = { ok: true; data: Workload } | { ok: false; message: string };

/** Data is loaded in try/catch and rendered outside it, so a render error still reaches the error boundary. */
async function load(): Promise<Loaded> {
  try {
    return { ok: true, data: await workload() };
  } catch (error) {
    return { ok: false, message: isProblem(error) ? problemMessage(error) : "We could not reach the Seed backend." };
  }
}

export async function WorkloadView() {
  const loaded = await load();
  if (!loaded.ok) {
    return (
      <p role="alert" className="flex items-center gap-2 text-sm text-danger [&_svg]:size-4">
        <AlertTriangle aria-hidden="true" />
        {loaded.message}
      </p>
    );
  }
  const { data } = loaded;
  const waiting = data.items.filter((item) => item.waiting > 0);
  if (waiting.length === 0) {
    return (
      <EmptyState
        icon={<CheckCheck />}
        title="Nothing is waiting"
        description="No application or hardship decision needs an officer or a checker right now."
      />
    );
  }
  const items = [...waiting].sort(
    (a, b) => a.role.localeCompare(b.role) || a.kind.localeCompare(b.kind) || a.slaBucket.localeCompare(b.slaBucket),
  );

  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2">
        {data.byRole.map((role) => (
          <Kpi
            key={role.role}
            label={`Waiting for a ${ROLE_NAMES[role.role].toLowerCase()}`}
            value={role.waiting.toLocaleString("en-US")}
            hint={role.overdue ? `${role.overdue.toLocaleString("en-US")} past the deadline` : "None past the deadline"}
          />
        ))}
      </div>
      <Card className="min-w-0">
        <CardHeader>
          <CardTitle>Waiting by deadline</CardTitle>
          <CardDescription>
            Each bar is a group of approvals with the same deadline state. Overdue bars end at the line for now; the others
            run from now to the oldest deadline in the group. As of {formatDateTime(data.asOf)}.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <WorkloadChartLoader
            rows={workloadRows()}
            bars={workloadBars(data)}
            now={data.asOf}
            labels={{ title: "Approvals waiting per role and deadline", now: "Now", sla: SLA_LABELS }}
          />
        </CardContent>
      </Card>
      <Card className="min-w-0">
        <CardHeader>
          <CardTitle>The same figures as a table</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableCaption className="sr-only">Approvals waiting per role, kind of work and deadline state</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">Role</TableHead>
                <TableHead scope="col">Work</TableHead>
                <TableHead scope="col">Deadline</TableHead>
                <TableHead scope="col" className="text-right">
                  Waiting
                </TableHead>
                <TableHead scope="col">Oldest deadline</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((item) => (
                <TableRow key={`${item.role}:${item.kind}:${item.slaBucket}`}>
                  <TableCell>{ROLE_NAMES[item.role]}</TableCell>
                  <TableCell>{KIND_NAMES[item.kind]}</TableCell>
                  <TableCell>
                    <StatusPill tone={SLA_TONES[item.slaBucket]}>{SLA_LABELS[item.slaBucket]}</StatusPill>
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{item.waiting.toLocaleString("en-US")}</TableCell>
                  <TableCell>{item.earliestSlaDueAt ? formatDateTime(item.earliestSlaDueAt) : "—"}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </>
  );
}

export function WorkloadSkeleton() {
  return (
    <div className="flex flex-col gap-6" aria-busy="true" aria-label="Loading the workload">
      <div className="grid gap-4 sm:grid-cols-2">
        <Skeleton className="h-33 rounded-xl" />
        <Skeleton className="h-33 rounded-xl" />
      </div>
      <Skeleton className="h-[400px] rounded-xl" />
      <Skeleton className="h-64 rounded-xl" />
    </div>
  );
}
