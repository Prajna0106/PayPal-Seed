import { Wordmark } from "@paypal-seed/ui/brand/wordmark";
import type { Route } from "next";
import Link from "next/link";
import type { ReactNode } from "react";
import { MobileMenu } from "@/features/nav/components/mobile-menu";
import { PrimaryNav } from "@/features/nav/components/primary-nav";
import { UserMenu } from "@/features/nav/components/user-menu";
import { requireSession } from "@/server/session";

/** The signed-in member area: a calm top bar with every route, then the page and a short honest footer. */
export default async function MemberLayout({ children }: { children: ReactNode }) {
  const session = await requireSession();
  return (
    <div className="flex min-h-dvh flex-col bg-canvas">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-50">
        Skip to content
      </a>
      <header className="sticky top-0 z-40 border-b border-hairline bg-canvas/85 backdrop-blur-md">
        <div className="mx-auto flex h-16 max-w-5xl items-center justify-between gap-4 px-6">
          <div className="flex items-center gap-2 lg:gap-8">
            <MobileMenu displayName={session.displayName} />
            <Link href={"/app" as Route} aria-label="Seed home">
              <Wordmark />
            </Link>
          </div>
          <PrimaryNav />
          <UserMenu displayName={session.displayName} />
        </div>
      </header>
      <main id="main" className="mx-auto w-full max-w-5xl flex-1 px-6 py-10">
        {children}
      </main>
      <footer className="border-t border-hairline">
        <p className="mx-auto max-w-5xl px-6 py-6 text-xs text-muted-ink text-pretty">
          Seed balances show what Seed holds and moves for you. PayPal does not share your wallet balance with apps.
        </p>
      </footer>
    </div>
  );
}
