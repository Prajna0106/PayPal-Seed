"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { ArrowRight, Loader2 } from "lucide-react";
import { useActionState } from "react";
import { signInWithMemberId, startAnaStory, type SignInState } from "../actions";

export function AnaStoryForm() {
  const [state, action, pending] = useActionState<SignInState>(startAnaStory, {});
  return (
    <form action={action} className="flex flex-col gap-3">
      <Button type="submit" size="lg" disabled={pending} className="w-full">
        {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
        {pending ? "Setting up Ana's story…" : "Start Ana's story"}
        {pending ? null : <ArrowRight aria-hidden="true" />}
      </Button>
      <p className="text-xs text-muted-ink">
        Creates a $1,200 loan, four lean weeks of income, and runs the daily scan. Takes a few seconds.
      </p>
      <p aria-live="polite" className="text-sm text-danger">
        {state.error}
      </p>
    </form>
  );
}

export function MemberIdForm() {
  const [state, action, pending] = useActionState<SignInState, FormData>(signInWithMemberId, {});
  return (
    <form action={action} className="flex flex-col gap-2">
      <Label htmlFor="memberId">Member id</Label>
      <div className="flex gap-2">
        <Input
          id="memberId"
          name="memberId"
          required
          autoComplete="off"
          spellCheck={false}
          placeholder="0197f3a2-5c1e-7b3a-9d10-3c4f1a2b9e01"
          aria-invalid={state.error ? true : undefined}
          aria-describedby={state.error ? "memberId-error" : undefined}
          className="h-10 font-mono text-sm"
        />
        <Button type="submit" variant="outline" disabled={pending}>
          Sign in
        </Button>
      </div>
      <p id="memberId-error" aria-live="polite" className="text-sm text-danger">
        {state.error}
      </p>
    </form>
  );
}
