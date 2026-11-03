"use client";

import { Label } from "@paypal-seed/ui/components/label";
import { Textarea } from "@paypal-seed/ui/components/textarea";
import { Check, X } from "lucide-react";
import { useState } from "react";
import { approveApplication, rejectApplication } from "../actions";
import { ActionDialog } from "./action-dialog";

/** The reason is controlled so a refused submission (React resets uncontrolled fields) does not lose what was typed. */
function ReasonField() {
  const [reason, setReason] = useState("");
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor="reject-reason">Reason</Label>
      <Textarea
        id="reject-reason"
        name="reason"
        required
        minLength={3}
        maxLength={500}
        rows={4}
        value={reason}
        onChange={(event) => setReason(event.target.value)}
        placeholder="Say why, in plain words. The next officer will read this."
      />
      <p className="text-xs text-muted-ink">{reason.length} / 500</p>
    </div>
  );
}

export interface ApplicationActionsProps {
  applicationId: string;
  /** Keys made on the server for this render: a retry of the same submission reuses its key. */
  keys: { approve: string; reject: string };
  /** Preformatted on the server: "$1,200 over 12 months". */
  summary: string;
  show: { sign: boolean; reject: boolean };
  /** Which of the two signatures this click would be. */
  signStep: "first" | "second";
  /** Why signing is not offered to this officer, when the page already knows (same officer as the first approval). */
  signBlocked?: string;
}

/** Approve and reject. Each one confirms first (paying out lives in the payout panel); the backend still decides (roles, separation of duties). */
export function ApplicationActions({ applicationId, keys, summary, show, signStep, signBlocked }: ApplicationActionsProps) {
  return (
    <div className="flex flex-col gap-3">
      {show.sign ? (
        signBlocked ? (
          <p role="status" className="rounded-xl bg-canvas-soft p-3 text-sm text-body">
            {signBlocked}
          </p>
        ) : (
          <ActionDialog
            triggerLabel={signStep === "first" ? "Approve (first of two)" : "Approve (second of two)"}
            triggerIcon={<Check aria-hidden="true" />}
            title="Approve this application?"
            description={
              <>
                <p>
                  {summary}. {signStep === "first" ? "A different officer must sign it next." : "This is the second signature, so it can then be paid out."}
                </p>
                <p className="mt-2">Check the fair check and the sizing before you sign. Your name is recorded.</p>
              </>
            }
            submitLabel="Approve"
            pendingLabel="Approving…"
            action={approveApplication}
            hidden={{ applicationId, key: keys.approve }}
          />
        )
      ) : null}

      {show.reject ? (
        <ActionDialog
          triggerLabel="Reject"
          triggerVariant="outline"
          triggerIcon={<X aria-hidden="true" />}
          title="Reject this application?"
          description={<p>{summary}. The member will be told it was not approved. A reason is required.</p>}
          submitLabel="Reject application"
          pendingLabel="Rejecting…"
          action={rejectApplication}
          hidden={{ applicationId, key: keys.reject }}
        >
          <ReasonField />
        </ActionDialog>
      ) : null}
    </div>
  );
}
