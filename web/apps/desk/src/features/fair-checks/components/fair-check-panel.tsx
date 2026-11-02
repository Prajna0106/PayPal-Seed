"use client";

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import type { FairCheckCard } from "../model";
import { DecisionForm } from "./decision-form";
import { FairCheckEvidence } from "./fair-check-evidence";

export interface FairCheckPanelProps {
  card: FairCheckCard;
  idempotencyKey: string;
  onDecided?: () => void;
}

/** Evidence first, then the decision (only while it is pending). Used in the queue's side sheet and on the page. */
export function FairCheckPanel({ card, idempotencyKey, onDecided }: FairCheckPanelProps) {
  return (
    <div className="flex flex-col gap-6">
      <FairCheckEvidence card={card} />
      {card.officerDecision === "PENDING" ? (
        <Card>
          <CardHeader>
            <CardTitle>Decide</CardTitle>
            <CardDescription>Approve, adjust within the safe limits, or decline. A reason is required.</CardDescription>
          </CardHeader>
          <CardContent>
            <DecisionForm card={card} idempotencyKey={idempotencyKey} {...(onDecided ? { onDecided } : {})} />
          </CardContent>
        </Card>
      ) : null}
    </div>
  );
}
