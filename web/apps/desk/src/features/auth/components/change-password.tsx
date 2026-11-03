"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { Loader2 } from "lucide-react";
import { useActionState } from "react";
import { changePassword, type PasswordState } from "../actions";

export function ChangePassword() {
  const [state, action, pending] = useActionState<PasswordState, FormData>(changePassword, {});
  return (
    <form action={action} className="flex flex-col gap-3 rounded-2xl border border-hairline bg-surface p-6 shadow-soft">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="currentPassword">Current password</Label>
        <Input id="currentPassword" name="currentPassword" type="password" required autoComplete="current-password" className="h-10" />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="newPassword">New password (12 or more characters)</Label>
        <Input id="newPassword" name="newPassword" type="password" required minLength={12} maxLength={128} autoComplete="new-password" className="h-10" />
      </div>
      <Button type="submit" className="self-start" disabled={pending}>
        {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
        Change password
      </Button>
      <p aria-live="polite" className={state.done ? "text-sm text-body" : "text-sm text-danger"}>
        {state.done ? "Your password is changed." : state.error}
      </p>
    </form>
  );
}
