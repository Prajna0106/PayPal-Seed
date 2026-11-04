"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { Loader2 } from "lucide-react";
import { useActionState } from "react";
import { createStaff, type CreateStaffState } from "../actions";
import { ROLES } from "./roles";

export function CreateStaffForm() {
  const [state, action, pending] = useActionState<CreateStaffState, FormData>(createStaff, {});
  return (
    <form key={state.created} action={action} className="flex flex-col gap-4 rounded-2xl border border-hairline bg-surface p-6 shadow-soft">
      <h2 className="text-lg font-medium text-ink">Add a staff member</h2>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="displayName">Name</Label>
        <Input id="displayName" name="displayName" required maxLength={80} autoComplete="off" className="h-10" />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="email">Work email (their user ID)</Label>
        <Input id="email" name="email" type="email" required maxLength={254} autoComplete="off" className="h-10" />
      </div>
      <fieldset className="flex flex-col gap-1">
        <legend className="mb-1 text-sm font-medium text-ink">Roles</legend>
        {ROLES.map((role) => (
          <label key={role.value} className="flex min-h-11 items-center gap-3 text-sm text-ink">
            <input type="checkbox" name="roles" value={role.value} className="size-4" />
            <span>
              {role.label} <span className="text-muted-ink">· {role.hint}</span>
            </span>
          </label>
        ))}
      </fieldset>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="memberId">Their own member ID (optional)</Label>
        <Input id="memberId" name="memberId" maxLength={36} autoComplete="off" spellCheck={false} className="h-10 font-mono" />
        <p className="text-xs text-muted-ink">If they also use Seed as a member, so they can never decide their own case.</p>
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="password">Starting password (12 or more characters)</Label>
        <Input id="password" name="password" type="password" required minLength={12} maxLength={128} autoComplete="new-password" className="h-10" />
        <p className="text-xs text-muted-ink">Tell them privately. They must choose their own the first time they log in.</p>
      </div>
      <Button type="submit" className="self-start" disabled={pending}>
        {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
        Add staff member
      </Button>
      <p aria-live="polite" className={state.created ? "text-sm text-body" : "text-sm text-danger"}>
        {state.created ? `${state.created} can now log in.` : state.error}
      </p>
    </form>
  );
}
