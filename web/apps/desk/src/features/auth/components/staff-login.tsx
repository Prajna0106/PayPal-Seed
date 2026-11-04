"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { Loader2 } from "lucide-react";
import { useActionState } from "react";
import { signInWithPassword, type LoginState } from "../actions";

export function StaffLogin({ next }: { next?: string }) {
  const [state, action, pending] = useActionState<LoginState, FormData>(signInWithPassword, {});
  return (
    <section className="rounded-2xl border border-hairline bg-surface p-6 shadow-soft">
      <form action={action} className="flex flex-col gap-3">
        {next ? <input type="hidden" name="next" value={next} /> : null}
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="email">Work email</Label>
          <Input id="email" name="email" type="email" required autoComplete="username" className="h-10" />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="password">Password</Label>
          <Input id="password" name="password" type="password" required autoComplete="current-password" className="h-10" />
        </div>
        <Button type="submit" size="lg" disabled={pending}>
          {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
          Log in
        </Button>
        <p aria-live="polite" className="text-sm text-danger">
          {state.error}
        </p>
      </form>
    </section>
  );
}
