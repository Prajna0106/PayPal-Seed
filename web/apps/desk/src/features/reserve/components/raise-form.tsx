"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@paypal-seed/ui/components/select";
import { toast } from "@paypal-seed/ui/components/sonner";
import { Textarea } from "@paypal-seed/ui/components/textarea";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { Info } from "lucide-react";
import { type FormEvent, startTransition, useActionState, useEffect, useRef, useState } from "react";
import { type ActionResult, raiseCoverRequest } from "../actions";
import { DEFAULT_CAPS, DECISION_WINDOW, RAISE_REASONS } from "../model";

export interface RaiseFormProps {
  /** Created on the server for this render; the action returns the next one after each answer. */
  idempotencyKey: string;
  /** Pre-fills from the link an officer followed (member, loan, hardship case). Already checked as UUIDs. */
  defaults?: { memberId?: string; loanId?: string; hardshipCaseId?: string };
  /** False for a checker: only officers can raise a request. */
  canRaise: boolean;
}

const initial: ActionResult = { status: "idle" };

/** The maker's step: ask the fund to pay part of one member's loan. The server refuses anything over its limits. */
export function RaiseForm({ idempotencyKey, defaults, canRaise }: RaiseFormProps) {
  const [state, formAction, pending] = useActionState(raiseCoverRequest, initial);
  const [reason, setReason] = useState("");
  // Every answer carries the key to use next; before the first answer it is the one made for this render.
  const key = state.status === "idle" ? idempotencyKey : state.nextKey;
  const formRef = useRef<HTMLFormElement>(null);

  // Clearing the controlled reason when an answer arrives is state derived from the action result, so it is set in render.
  const [handled, setHandled] = useState(state);
  if (state !== handled) {
    setHandled(state);
    if (state.status === "ok") {
      setReason("");
    }
  }

  useEffect(() => {
    if (state.status === "idle") {
      return;
    }
    if (state.status === "ok") {
      toast.success(state.message);
      formRef.current?.reset();
    }
  }, [state]);

  // A plain action would clear every field after an error; keep what the officer typed and reset only on success.
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const formData = new FormData(event.currentTarget);
    startTransition(() => formAction(formData));
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Raise a cover request</CardTitle>
        <CardDescription>
          The fund pays the loan, not the member. A second officer with the checker role decides it, ideally within {DECISION_WINDOW}.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {canRaise ? (
          <form ref={formRef} onSubmit={submit} className="flex flex-col gap-5">
            <input type="hidden" name="key" value={key} />
            <div className="grid gap-5 sm:grid-cols-2">
              <div className="flex flex-col gap-2">
                <Label htmlFor="cover-member">Member reference</Label>
                <Input
                  id="cover-member"
                  name="memberId"
                  required
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="Full member UUID"
                  defaultValue={defaults?.memberId}
                  className="font-mono text-xs"
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="cover-loan">Loan reference</Label>
                <Input
                  id="cover-loan"
                  name="loanId"
                  required
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="Full loan UUID"
                  defaultValue={defaults?.loanId}
                  className="font-mono text-xs"
                />
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="cover-amount">Amount (US dollars)</Label>
                <Input
                  id="cover-amount"
                  name="amount"
                  required
                  inputMode="decimal"
                  autoComplete="off"
                  placeholder="50"
                  aria-describedby="cover-amount-help"
                />
                <p id="cover-amount-help" className="text-xs text-muted-ink">
                  Default limits: {formatMoney(DEFAULT_CAPS.perRequestCents)} per request and{" "}
                  {formatMoney(DEFAULT_CAPS.perMemberYearCents)} per member each calendar year, counting pending requests. It also cannot
                  exceed what the loan still owes or what the fund holds. The server checks all of this.
                </p>
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="cover-reason">Reason</Label>
                <Select name="reasonCode" required value={reason} onValueChange={setReason}>
                  <SelectTrigger id="cover-reason" className="w-full">
                    <SelectValue placeholder="Choose a reason" />
                  </SelectTrigger>
                  <SelectContent>
                    {RAISE_REASONS.map((item) => (
                      <SelectItem key={item.code} value={item.code}>
                        {item.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-2 sm:col-span-2">
                <Label htmlFor="cover-case">
                  Hardship case reference <span className="font-normal text-muted-ink">(optional)</span>
                </Label>
                <Input
                  id="cover-case"
                  name="hardshipCaseId"
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="The case that needs the bridge"
                  defaultValue={defaults?.hardshipCaseId}
                  className="font-mono text-xs"
                />
              </div>
              <div className="flex flex-col gap-2 sm:col-span-2">
                <Label htmlFor="cover-note">
                  Note <span className="font-normal text-muted-ink">(optional unless the reason is Other)</span>
                </Label>
                <Textarea id="cover-note" name="note" maxLength={500} rows={3} placeholder="What makes the bridge necessary?" />
              </div>
            </div>

            <div aria-live="polite" className="min-h-5">
              {state.status === "error" ? (
                <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
                  {state.message}
                </p>
              ) : null}
            </div>

            <div className="flex flex-wrap items-center gap-3">
              <Button type="submit" disabled={pending} className="min-h-11 w-full sm:w-auto">
                {pending ? "Raising…" : "Raise request"}
              </Button>
              <p className="text-xs text-muted-ink">Raising a request does not approve it. A maker signs first, then a different checker.</p>
            </div>
          </form>
        ) : (
          <p className="flex items-start gap-2 rounded-xl bg-canvas-soft p-3 text-sm text-body [&_svg]:mt-0.5 [&_svg]:size-4 [&_svg]:shrink-0">
            <Info aria-hidden="true" />
            Only an officer can raise a cover request. As a checker you decide the requests below. Switch persona (bottom left) to raise one.
          </p>
        )}
      </CardContent>
    </Card>
  );
}
