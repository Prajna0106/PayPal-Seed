"use client";

import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@paypal-seed/ui/components/alert-dialog";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Button } from "@paypal-seed/ui/components/button";
import { formatDateTime } from "@paypal-seed/ui/lib/format";
import { CircleAlert, Loader2 } from "lucide-react";
import { useState, useTransition } from "react";
import { changeConsent } from "../actions";
import { CONSENT_COPY } from "../copy";
import type { ConsentTypeName } from "../schema";

export interface ConsentCardProps {
  type: ConsentTypeName;
  current: { version: string; grantedAt: string } | null;
  past: { version: string; grantedAt: string; revokedAt: string | null }[];
  /** What the fair check reads and never reads, for the income consent only. */
  detail?: { used: string[]; neverUsed: string[] } | null;
}

/** One agreement: its state, the version and date, a way to agree, and a confirmed way to withdraw. */
export function ConsentCard({ type, current, past, detail }: ConsentCardProps) {
  const copy = CONSENT_COPY[type];
  const [open, setOpen] = useState(false);
  const [pending, start] = useTransition();
  const [error, setError] = useState<string | null>(null);

  function run(intent: "grant" | "revoke") {
    setError(null);
    start(async () => {
      const result = await changeConsent(type, intent);
      if (result.ok) {
        setOpen(false);
      } else {
        setError(result.message);
      }
    });
  }

  return (
    <li className="flex flex-col gap-4 rounded-2xl border border-hairline bg-surface p-5 sm:p-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex flex-col gap-1">
          <h3 className="text-[17px] font-medium text-ink">{copy.title}</h3>
          <p className="text-sm text-body text-pretty">{copy.about}</p>
          <p className="text-sm text-muted-ink">
            {current ? (
              <>
                Agreed {formatDateTime(current.grantedAt, { dateStyle: "medium" })} · version {current.version}
              </>
            ) : past[0]?.revokedAt ? (
              <>Withdrawn {formatDateTime(past[0].revokedAt, { dateStyle: "medium" })}</>
            ) : (
              "Not agreed yet"
            )}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <StatusPill tone={current ? "success" : "neutral"}>{current ? "On" : "Off"}</StatusPill>
          {current ? (
            <AlertDialog open={open} onOpenChange={setOpen}>
              <AlertDialogTrigger asChild>
                <Button variant="outline" className="h-11">
                  Withdraw
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Withdraw “{copy.title}”?</AlertDialogTitle>
                  <AlertDialogDescription>{copy.onRevoke} You can agree again here at any time.</AlertDialogDescription>
                </AlertDialogHeader>
                {error ? (
                  <p role="alert" className="flex items-start gap-2 rounded-lg bg-danger-soft px-3 py-2.5 text-sm text-danger">
                    <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
                    {error}
                  </p>
                ) : null}
                <AlertDialogFooter>
                  <AlertDialogCancel className="h-11">Keep it</AlertDialogCancel>
                  <Button className="h-11" onClick={() => run("revoke")} disabled={pending} aria-busy={pending}>
                    {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
                    Yes, withdraw
                  </Button>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          ) : (
            <Button className="h-11" onClick={() => run("grant")} disabled={pending} aria-busy={pending}>
              {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
              Agree
            </Button>
          )}
        </div>
      </div>

      {!current && error ? (
        <p role="alert" className="flex items-start gap-2 rounded-lg bg-danger-soft px-3 py-2.5 text-sm text-danger">
          <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          {error}
        </p>
      ) : null}

      {detail && (detail.used.length > 0 || detail.neverUsed.length > 0) ? (
        <div className="grid gap-4 border-t border-hairline pt-4 text-sm sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <h4 className="font-medium text-ink">What the fair check reads</h4>
            <ul className="flex list-disc flex-col gap-1 pl-5 text-body">
              {detail.used.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </div>
          <div className="flex flex-col gap-1.5">
            <h4 className="font-medium text-ink">What it never reads</h4>
            <ul className="flex list-disc flex-col gap-1 pl-5 text-body">
              {detail.neverUsed.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </div>
        </div>
      ) : null}

      {past.length > 0 ? (
        <details className="text-sm">
          <summary className="flex min-h-11 cursor-pointer items-center text-muted-ink focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none">
            Earlier agreements ({past.length})
          </summary>
          <ul className="flex flex-col gap-1.5 pb-1 text-body">
            {past.map((line) => (
              <li key={`${line.version}-${line.grantedAt}`}>
                Version {line.version}: agreed {formatDateTime(line.grantedAt, { dateStyle: "medium" })}
                {line.revokedAt ? `, ended ${formatDateTime(line.revokedAt, { dateStyle: "medium" })}` : ""}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </li>
  );
}
