import { GradientOrb } from "@paypal-seed/ui/brand/gradient-orb";
import { Wordmark } from "@paypal-seed/ui/brand/wordmark";
import { Separator } from "@paypal-seed/ui/components/separator";
import type { Metadata } from "next";
import Link from "next/link";
import { AnaStoryForm, MemberIdForm } from "@/features/auth/components/sign-in-forms";

export const metadata: Metadata = { title: "Demo sign-in" };

export default function LoginPage() {
  return (
    <main className="relative isolate grid min-h-dvh place-items-center overflow-hidden px-6 py-16">
      <GradientOrb tone="peach" blend="lavender" className="-top-32 -left-24 -z-10 size-[34rem]" />
      <GradientOrb tone="mint" blend="sky" className="-right-32 -bottom-40 -z-10 size-[30rem]" />
      <div className="flex w-full max-w-md flex-col gap-8">
        <Link href="/" className="self-start" aria-label="PayPal Seed home">
          <Wordmark />
        </Link>
        <div className="flex flex-col gap-2">
          <h1 className="text-display-lg text-ink">Step into the demo</h1>
          <p className="text-body">
            Real sign-in with PayPal arrives with the identity feature. For now, start a fresh story or continue as a
            member you already created.
          </p>
        </div>
        <section className="flex flex-col gap-5 rounded-2xl border border-hairline bg-surface p-6 shadow-soft">
          <div className="flex flex-col gap-1">
            <h2 className="text-lg font-medium text-ink">Ana, home baker</h2>
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
