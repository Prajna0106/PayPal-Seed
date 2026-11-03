import { Wordmark } from "@paypal-seed/ui/brand/wordmark";
import Link from "next/link";
import type { ReactNode } from "react";

/** Pages anyone can open without signing in: a partner checking a record, a donor looking at the fund. No cookies are read here. */
export default function PublicLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col bg-canvas">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-50">
        Skip to content
      </a>
      <header className="border-b border-hairline">
        <div className="mx-auto flex h-16 max-w-4xl items-center justify-between px-6">
          <Link href="/" aria-label="PayPal Seed home">
            <Wordmark />
          </Link>
          <Link href="/fund" className="text-sm text-body transition-colors hover:text-ink">
            Safety fund
          </Link>
        </div>
      </header>
      <main id="main" className="mx-auto w-full max-w-4xl flex-1 px-6 py-10 sm:py-14">
        {children}
      </main>
      <footer className="border-t border-hairline">
        <p className="mx-auto max-w-4xl px-6 py-6 text-sm text-muted-ink">PayPal Seed. Savings and fair microloans inside PayPal.</p>
      </footer>
    </div>
  );
}
