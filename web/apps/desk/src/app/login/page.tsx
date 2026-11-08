import { GradientOrb } from "@paypal-seed/ui/brand/gradient-orb";
import { Wordmark } from "@paypal-seed/ui/brand/wordmark";
import type { Metadata } from "next";
import { JudgeAccess } from "@/features/auth/components/judge-access";
import { StaffLogin } from "@/features/auth/components/staff-login";
import { env } from "@/server/env";

export const metadata: Metadata = { title: "Sign in" };

export default async function LoginPage({ searchParams }: PageProps<"/login">) {
  const { next, ended } = await searchParams;
  const nextPath = typeof next === "string" ? next : undefined;
  return (
    <main className="relative isolate grid min-h-dvh place-items-center overflow-hidden px-6 py-12">
      <GradientOrb tone="sky" blend="lavender" className="-top-40 -right-20 -z-10 size-[36rem]" />
      <GradientOrb tone="mint" blend="peach" className="-bottom-48 -left-24 -z-10 size-[30rem]" />
      <div
        className={
          env.judgeAccess
            ? "grid w-full max-w-5xl items-center gap-10 lg:grid-cols-[minmax(0,26rem)_1fr] lg:gap-16"
            : "w-full max-w-md"
        }
      >
        <div className="flex flex-col gap-8">
          <div className="flex items-center gap-3">
            <Wordmark />
            <span className="rounded-full bg-ink px-2.5 py-0.5 text-xs font-medium text-white">Desk</span>
          </div>
          <div className="flex flex-col gap-2">
            <h1 className="text-display-lg text-ink">Log in to the desk</h1>
            {ended ? (
              <p role="alert" className="text-sm text-danger">
                You were logged out to keep your account safe. Please log in again.
              </p>
            ) : null}
          </div>
          <StaffLogin {...(nextPath ? { next: nextPath } : {})} />
        </div>
        {env.judgeAccess ? <JudgeAccess /> : null}
      </div>
    </main>
  );
}
