import { GradientOrb } from "@paypal-seed/ui/brand/gradient-orb";
import { Wordmark } from "@paypal-seed/ui/brand/wordmark";
import { Avatar, AvatarFallback } from "@paypal-seed/ui/components/avatar";
import { ArrowRight } from "lucide-react";
import type { Metadata } from "next";
import { signInAs } from "@/features/auth/actions";
import { StaffLogin } from "@/features/auth/components/staff-login";
import { env } from "@/server/env";
import { PERSONAS } from "@/server/personas";

export const metadata: Metadata = { title: "Sign in" };

export default async function LoginPage({ searchParams }: PageProps<"/login">) {
  const { next, ended } = await searchParams;
  const nextPath = typeof next === "string" ? next : undefined;
  return (
    <main className="relative isolate grid min-h-dvh place-items-center overflow-hidden px-6 py-16">
      <GradientOrb tone="sky" blend="lavender" className="-top-40 -right-20 -z-10 size-[36rem]" />
      <GradientOrb tone="mint" blend="peach" className="-bottom-48 -left-24 -z-10 size-[30rem]" />
      <div className="flex w-full max-w-lg flex-col gap-8">
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
        {env.demoLogin ? (
          <>
            <div className="flex flex-col gap-1">
              <h2 className="text-lg font-medium text-ink">Or use a demo persona</h2>
              <p className="text-sm text-body">
                Aira makes decisions, Priyam countersigns them, and Prajna runs operations. The backend checks every role.
              </p>
            </div>
            <ul className="flex flex-col gap-3">
              {PERSONAS.map((persona) => (
                <li key={persona.id}>
                  <form action={signInAs}>
                    <input type="hidden" name="personaId" value={persona.id} />
                    {nextPath ? <input type="hidden" name="next" value={nextPath} /> : null}
                    <button
                      type="submit"
                      className="group flex w-full items-center gap-4 rounded-2xl border border-hairline bg-surface p-4 text-left transition-shadow hover:shadow-lift focus-visible:shadow-lift"
                    >
                      <Avatar className="size-11">
                        <AvatarFallback className="bg-surface-strong font-display text-lg text-ink">
                          {persona.name.slice(0, 1)}
                        </AvatarFallback>
                      </Avatar>
                      <span className="flex flex-1 flex-col">
                        <span className="font-medium text-ink">{persona.name}</span>
                        <span className="text-sm text-muted-ink">
                          {persona.title} · {persona.roles.join(", ")}
                        </span>
                      </span>
                      <ArrowRight
                        aria-hidden="true"
                        className="size-4 text-muted-ink transition-transform group-hover:translate-x-0.5"
                      />
                    </button>
                  </form>
                </li>
              ))}
            </ul>
          </>
        ) : null}
      </div>
    </main>
  );
}
