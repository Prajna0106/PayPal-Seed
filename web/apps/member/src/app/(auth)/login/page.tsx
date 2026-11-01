import { GradientOrb } from "@paypal-seed/ui/brand/gradient-orb";
import { Wordmark } from "@paypal-seed/ui/brand/wordmark";
import { Button } from "@paypal-seed/ui/components/button";
import { Separator } from "@paypal-seed/ui/components/separator";
import type { Metadata } from "next";
import Link from "next/link";
import { AnaStoryForm, MemberIdForm } from "@/features/auth/components/sign-in-forms";

export const metadata: Metadata = { title: "Log in" };

const LOGIN_ERRORS: Record<string, string> = {
  expired: "That login expired. Please try again.",
  cancelled: "You cancelled the login at PayPal.",
  rejected: "PayPal didn't accept that login. Please try again.",
  identity: "PayPal couldn't share your details. Please log in with a personal PayPal account, not a business one.",
  suspended: "This account is suspended. Please contact support.",
  unavailable: "We can't log you in right now. Please try again in a few minutes.",
  "session-ended": "You were logged out to keep your account safe. Please log in again.",
};

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const { error } = await searchParams;
  const message = error ? (LOGIN_ERRORS[error] ?? LOGIN_ERRORS.unavailable) : null;

  return (
    <main className="relative isolate grid min-h-dvh place-items-center overflow-hidden px-6 py-16">
      <GradientOrb tone="peach" blend="lavender" className="-top-32 -left-24 -z-10 size-[34rem]" />
      <GradientOrb tone="mint" blend="sky" className="-right-32 -bottom-40 -z-10 size-[30rem]" />
      <div className="flex w-full max-w-md flex-col gap-8">
        <Link href="/" className="self-start" aria-label="PayPal Seed home">
          <Wordmark />
        </Link>
        <div className="flex flex-col gap-2">
          <h1 className="text-display-lg text-ink">Welcome to Seed</h1>
          <p className="text-body">Log in with your PayPal account to see your savings and loans.</p>
        </div>
        <section className="flex flex-col gap-3 rounded-2xl border border-hairline bg-surface p-6 shadow-soft">
          {message ? (
            <p role="alert" className="text-sm text-danger">
              {message}
            </p>
          ) : null}
          {/* A plain link on purpose: a prefetch would start a login nobody asked for. */}
          <Button asChild size="lg" className="w-full">
            <a href="/api/auth/paypal/start">Log in with PayPal</a>
          </Button>
        </section>
        <section className="flex flex-col gap-5 rounded-2xl border border-hairline bg-surface p-6 shadow-soft">
          <div className="flex flex-col gap-1">
            <h2 className="text-lg font-medium text-ink">Or try the demo as Ana, home baker</h2>
            <p className="text-sm text-body">
              Saved $75 a month, borrowed $1,200 for a mixer. This month her sales dropped by half.
            </p>
          </div>
          <AnaStoryForm />
          <Separator />
          <MemberIdForm />
        </section>
      </div>
    </main>
  );
}
