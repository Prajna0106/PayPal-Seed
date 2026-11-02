import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Avatar, AvatarFallback } from "@paypal-seed/ui/components/avatar";
import { Button } from "@paypal-seed/ui/components/button";
import { LogOut } from "lucide-react";
import type { Metadata } from "next";
import { signOut } from "@/features/auth/actions";
import { ConsentCard } from "@/features/settings/components/consent-card";
import { ProfileForm } from "@/features/settings/components/profile-form";
import { maskEmail } from "@/features/settings/copy";
import { getSettings } from "@/features/settings/server/queries";
import { requireSession } from "@/server/session";

export const metadata: Metadata = { title: "Settings" };

export default async function SettingsPage() {
  const [session, { profile, consents, income }] = await Promise.all([requireSession(), getSettings()]);
  const name = profile?.displayName?.trim() || session.displayName;

  return (
    <div className="flex max-w-3xl flex-col gap-12">
      <PageHeader eyebrow="Your account" title="Settings" description="Your profile, and everything you have agreed to. You are always in charge of both." />

      <section aria-labelledby="who" className="flex flex-col gap-4">
        <h2 id="who" className="text-display-sm text-ink">
          Signed in with PayPal
        </h2>
        <div className="flex flex-col gap-4 rounded-2xl border border-hairline bg-surface p-5 sm:flex-row sm:items-center sm:justify-between sm:p-6">
          <div className="flex items-center gap-4">
            <Avatar className="size-12">
              <AvatarFallback className="bg-surface-strong text-base text-ink">{name.slice(0, 1).toUpperCase()}</AvatarFallback>
            </Avatar>
            <div className="flex flex-col gap-0.5">
              <p className="flex flex-wrap items-center gap-2 text-[17px] font-medium text-ink">
                {name}
                {profile?.paypalVerified ? <StatusPill tone="success">PayPal verified</StatusPill> : null}
              </p>
              <p className="text-sm text-body">{profile ? maskEmail(profile.email) : "No PayPal sign-in yet"}</p>
            </div>
          </div>
          <form action={signOut}>
            <Button type="submit" variant="outline" className="h-11">
              <LogOut aria-hidden="true" />
              Sign out
            </Button>
          </form>
        </div>
      </section>

      {profile ? (
        <>
          <section aria-labelledby="profile" className="flex flex-col gap-4">
            <h2 id="profile" className="text-display-sm text-ink">
              Profile
            </h2>
            <div className="rounded-2xl border border-hairline bg-surface p-5 sm:p-6">
              <ProfileForm displayName={profile.displayName ?? session.displayName} language={profile.language} walletPreference={profile.walletPreference} />
            </div>
          </section>

          <section aria-labelledby="consents" className="flex flex-col gap-4">
            <div className="flex flex-col gap-1">
              <h2 id="consents" className="text-display-sm text-ink">
                What you have agreed to
              </h2>
              <p className="text-body text-pretty">Withdrawing takes effect straight away. Nothing is ever deleted from this list, so you can always see what you agreed and when.</p>
            </div>
            <ul className="flex flex-col gap-4">
              {consents.map((consent) => (
                <ConsentCard
                  key={consent.type}
                  type={consent.type}
                  current={consent.current ? { version: consent.current.version, grantedAt: consent.current.grantedAt } : null}
                  past={consent.past.map((line) => ({ version: line.version, grantedAt: line.grantedAt, revokedAt: line.revokedAt ?? null }))}
                  detail={consent.type === "INCOME_READ" && income ? { used: income.used, neverUsed: income.neverUsed } : null}
                />
              ))}
            </ul>
          </section>
        </>
      ) : (
        <p className="rounded-2xl border border-dashed border-hairline-strong bg-canvas-soft p-6 text-body text-pretty">
          Your profile and agreements appear here after you log in with PayPal. This sign-in is a demo one, so there is nothing to change yet.
        </p>
      )}
    </div>
  );
}
