"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { Loader2 } from "lucide-react";
import { useActionState } from "react";
import { type ChangeStaffState, resetStaffPassword } from "../actions";

export function ResetPasswordForm({ staffId }: { staffId: string }) {
  const [state, action, pending] = useActionState<ChangeStaffState, FormData>(resetStaffPassword, {});
  return (
    <form key={state.done} action={action} className="flex flex-col gap-4 rounded-2xl border border-hairline bg-surface p-6 shadow-soft">
      <h2 className="text-lg font-medium text-ink">Reset password</h2>
      <p className="text-sm text-body">For a lost password. They&apos;re logged out everywhere and must choose their own next time.</p>
      <input type="hidden" name="staffId" value={staffId} />
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="password">New starting password (12 or more characters)</Label>
        <Input id="password" name="password" type="password" required minLength={12} maxLength={128} autoComplete="new-password" className="h-10" />
      </div>
      <Button type="submit" variant="outline" className="self-start" disabled={pending}>
        {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
        Reset password
      </Button>
      <p aria-live="polite" className={state.done ? "text-sm text-body" : "text-sm text-danger"}>
        {state.done ?? state.error}
      </p>
    </form>
  );
}
