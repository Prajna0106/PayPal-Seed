"use client";

import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
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
import { Button } from "@paypal-seed/ui/components/button";
import { formatDateTime } from "@paypal-seed/ui/lib/format";
import { useActionState, useState } from "react";
import { revokeShare, type RevokeState } from "../actions";
import { DETAIL_CHOICES, type ShareView } from "../model";

const STATUS: Record<ShareView["status"], { label: string; tone: "success" | "neutral" | "warning" }> = {
  ACTIVE: { label: "Active", tone: "success" },
  EXPIRED: { label: "Expired", tone: "neutral" },
  REVOKED: { label: "Stopped", tone: "warning" },
};

export function ShareList({ shares }: { shares: ShareView[] }) {
  if (shares.length === 0) {
    return <p className="text-sm text-body">You have not shared your record yet.</p>;
  }
  return (
    <ul className="divide-y divide-hairline rounded-2xl border border-hairline bg-surface">
      {shares.map((share) => (
        <ShareRow key={share.shareId} share={share} />
      ))}
    </ul>
  );
}

function ShareRow({ share }: { share: ShareView }) {
  const status = STATUS[share.status];
  const detail = DETAIL_CHOICES.find((choice) => choice.value === share.detail)?.title ?? share.detail;
  return (
    <li className="flex flex-col gap-3 px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <StatusPill tone={status.tone}>{status.label}</StatusPill>
          <span className="text-sm font-medium text-ink">{detail}</span>
        </div>
        <p className="text-sm text-body">
          Made {formatDateTime(share.issuedAt, { dateStyle: "medium" })}
          {share.status === "REVOKED" && share.revokedAt
            ? `, stopped ${formatDateTime(share.revokedAt, { dateStyle: "medium" })}`
            : share.status === "EXPIRED"
              ? `, ended ${formatDateTime(share.expiresAt, { dateStyle: "medium" })}`
              : `, works until ${formatDateTime(share.expiresAt)}`}
        </p>
      </div>
      {share.status === "ACTIVE" ? <RevokeButton shareId={share.shareId} /> : null}
    </li>
  );
}

function RevokeButton({ shareId }: { shareId: string }) {
  const [open, setOpen] = useState(false);
  const [state, action, pending] = useActionState<RevokeState, FormData>(revokeShare, { status: "idle" });

  // No effect to close the dialog: the action revalidates the page, the link turns "Stopped" and this button goes away.
  return (
    <AlertDialog open={open} onOpenChange={(next) => !pending && setOpen(next)}>
      <AlertDialogTrigger asChild>
        <Button variant="outline" size="sm" className="min-h-11 self-start sm:self-auto">
          Stop sharing
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <form action={action} className="flex flex-col gap-4">
          <input type="hidden" name="shareId" value={shareId} />
          <AlertDialogHeader>
            <AlertDialogTitle>Stop sharing this link?</AlertDialogTitle>
            <AlertDialogDescription>
              Anyone who opens it afterwards will see that it is not valid. You can always make a new link.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {state.status === "error" ? (
            <p role="alert" className="text-sm text-danger">
              {state.message}
            </p>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={pending}>Keep it</AlertDialogCancel>
            <Button type="submit" disabled={pending} aria-busy={pending}>
              Stop sharing
            </Button>
          </AlertDialogFooter>
        </form>
      </AlertDialogContent>
    </AlertDialog>
  );
}
