import { Wordmark } from "@paypal-seed/ui/brand/wordmark";
import { Avatar, AvatarFallback } from "@paypal-seed/ui/components/avatar";
import { Button } from "@paypal-seed/ui/components/button";
import { LogOut } from "lucide-react";
import Link from "next/link";
import type { ReactNode } from "react";
import { signOut } from "@/features/auth/actions";
import { requireSession } from "@/server/session";

/** The signed-in member area: a calm top bar, then the page. */
export default async function MemberLayout({ children }: { children: ReactNode }) {
  const session = await requireSession();
  return (
    <div className="flex min-h-dvh flex-col bg-canvas">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-50">
        Skip to content
      </a>
      <header className="sticky top-0 z-40 border-b border-hairline bg-canvas/85 backdrop-blur-md">
        <nav aria-label="Member" className="mx-auto flex h-16 max-w-5xl items-center justify-between gap-4 px-6">
          <Link href="/app" aria-label="Your loans">
            <Wordmark />
          </Link>
          <div className="flex items-center gap-3">
            <span className="hidden text-sm text-body sm:inline">{session.displayName}</span>
            <Avatar className="size-8">
              <AvatarFallback className="bg-surface-strong text-xs text-ink">
                {session.displayName.slice(0, 1).toUpperCase()}
              </AvatarFallback>
            </Avatar>
            <form action={signOut}>
              <Button type="submit" variant="ghost" size="icon" aria-label="Sign out">
                <LogOut aria-hidden="true" />
              </Button>
            </form>
          </div>
        </nav>
      </header>
      <main id="main" className="mx-auto w-full max-w-5xl flex-1 px-6 py-10">
        {children}
      </main>
    </div>
  );
}
