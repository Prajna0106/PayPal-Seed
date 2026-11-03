"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { Loader2 } from "lucide-react";
import { useActionState } from "react";
import { changeStaffAccess, type ChangeStaffState } from "../actions";
import { ROLES } from "./roles";

export interface StaffAccessFormProps {
  staffId: string;
  roles: readonly string[];
  active: boolean;
  linkedMemberId: string | null;
}

export function StaffAccessForm({ staffId, roles, active, linkedMemberId }: StaffAccessFormProps) {
  const [state, action, pending] = useActionState<ChangeStaffState, FormData>(changeStaffAccess, {});
  return (
    <form action={action} className="flex flex-col gap-4 rounded-2xl border border-hairline bg-surface p-6 shadow-soft">
      <h2 className="text-lg font-medium text-ink">Access</h2>
      <input type="hidden" name="staffId" value={staffId} />
      <input type="hidden" name="linkedBefore" value={linkedMemberId ? "true" : "false"} />
      <fieldset className="flex flex-col gap-1">
        <legend className="mb-1 text-sm font-medium text-ink">Roles</legend>
        {ROLES.map((role) => (
          <label key={role.value} className="flex min-h-11 items-center gap-3 text-sm text-ink">
            <input type="checkbox" name="roles" value={role.value} defaultChecked={roles.includes(role.value)} className="size-4" />
            <span>
              {role.label} <span className="text-muted-ink">· {role.hint}</span>
            </span>
          </label>
        ))}
      </fieldset>
      <label className="flex min-h-11 items-center gap-3 text-sm text-ink">
        <input type="checkbox" name="active" defaultChecked={active} className="size-4" />
        <span>
          Can use the desk <span className="text-muted-ink">· Untick to suspend. They&apos;re refused on their next click.</span>
        </span>
      </label>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="memberId">Their own member ID</Label>
        <Input id="memberId" name="memberId" defaultValue={linkedMemberId ?? ""} maxLength={36} autoComplete="off" spellCheck={false}
          className="h-10 font-mono" />
        <p className="text-xs text-muted-ink">Leave empty if they aren&apos;t a member. Clearing it removes the link.</p>
      </div>
      <Button type="submit" className="self-start" disabled={pending}>
        {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
        Save access
      </Button>
      <p aria-live="polite" className={state.done ? "text-sm text-body" : "text-sm text-danger"}>
        {state.done ?? state.error}
      </p>
    </form>
  );
}
