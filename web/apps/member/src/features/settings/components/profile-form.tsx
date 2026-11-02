"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { CheckCircle2, CircleAlert, Loader2 } from "lucide-react";
import { useActionState, useId, useTransition } from "react";
import { type SettingsState, updateProfile } from "../actions";

const IDLE: SettingsState = { status: "idle" };

const LANGUAGES = [
  { value: "en", label: "English" },
  { value: "es", label: "Español" },
] as const;

const WALLETS = [
  { value: "PAYPAL", label: "PayPal", hint: "Loan money goes to your PayPal account." },
  { value: "VENMO", label: "Venmo", hint: "Loan money goes to your Venmo, where PayPal allows it." },
] as const;

export interface ProfileFormProps {
  displayName: string;
  language: "en" | "es";
  walletPreference: "PAYPAL" | "VENMO";
}

/** Name, language and where loan money is sent. Radios are native, so they work without any script. */
export function ProfileForm(props: ProfileFormProps) {
  const id = useId();
  const [state, action, pending] = useActionState<SettingsState, FormData>(updateProfile, IDLE);
  const [, startTransition] = useTransition();

  return (
    // Submitted from a handler rather than `action`, so a refusal does not reset what the member typed.
    <form
      onSubmit={(event) => {
        event.preventDefault();
        const data = new FormData(event.currentTarget);
        startTransition(() => action(data));
      }}
      className="flex flex-col gap-6"
    >
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-name`} className="text-ink">
          Name
        </Label>
        <Input
          id={`${id}-name`}
          name="displayName"
          defaultValue={props.displayName}
          maxLength={120}
          required
          autoComplete="name"
          aria-describedby={`${id}-name-hint`}
          className="h-11 max-w-md"
        />
        <p id={`${id}-name-hint`} className="text-sm text-muted-ink">
          How Seed greets you. It does not change your PayPal name.
        </p>
      </div>

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium text-ink">Language</legend>
        <div className="flex flex-wrap gap-3">
          {LANGUAGES.map((option) => (
            <label
              key={option.value}
              className="flex min-h-11 cursor-pointer items-center gap-2.5 rounded-full border border-hairline-strong bg-surface px-4 text-[15px] has-[:checked]:border-ink has-[:checked]:bg-surface-strong has-[:focus-visible]:ring-[3px] has-[:focus-visible]:ring-ring/50"
            >
              <input type="radio" name="language" value={option.value} defaultChecked={props.language === option.value} className="accent-ink" />
              {option.label}
            </label>
          ))}
        </div>
      </fieldset>

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium text-ink">Where loan money is sent</legend>
        <div className="grid gap-3 sm:grid-cols-2">
          {WALLETS.map((option) => (
            <label
              key={option.value}
              className="flex min-h-11 cursor-pointer items-start gap-3 rounded-2xl border border-hairline-strong bg-surface p-4 has-[:checked]:border-ink has-[:checked]:bg-surface-strong has-[:focus-visible]:ring-[3px] has-[:focus-visible]:ring-ring/50"
            >
              <input
                type="radio"
                name="walletPreference"
                value={option.value}
                defaultChecked={props.walletPreference === option.value}
                className="mt-1 accent-ink"
              />
              <span className="flex flex-col gap-0.5">
                <span className="text-[15px] font-medium text-ink">{option.label}</span>
                <span className="text-sm text-muted-ink">{option.hint}</span>
              </span>
            </label>
          ))}
        </div>
      </fieldset>

      <div className="flex flex-col gap-3">
        <Button type="submit" className="h-11 self-start" disabled={pending} aria-busy={pending}>
          {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
          Save
        </Button>
        <div aria-live="polite" className="empty:hidden">
          {state.status === "ok" ? (
            <p className="flex items-center gap-2 text-sm text-success">
              <CheckCircle2 aria-hidden="true" className="size-4" />
              {state.message}
            </p>
          ) : null}
          {state.status === "error" ? (
            <p className="flex items-start gap-2 rounded-lg bg-danger-soft px-3 py-2.5 text-sm text-danger">
              <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              {state.message}
            </p>
          ) : null}
        </div>
      </div>
    </form>
  );
}
