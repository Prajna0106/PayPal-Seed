"use client";

import { Avatar, AvatarFallback } from "@paypal-seed/ui/components/avatar";
import { Button } from "@paypal-seed/ui/components/button";
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetTitle, SheetTrigger } from "@paypal-seed/ui/components/sheet";
import { cn } from "@paypal-seed/ui/lib/utils";
import { LogOut, Menu } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { signOut } from "@/features/auth/actions";
import { isActive, NAV_ITEMS } from "../items";

/**
 * The small-screen navigation: a sheet from the left. It is a modal dialog, so focus stays inside and Escape closes
 * it; each link is wrapped in `SheetClose`, so choosing a route closes it too.
 */
export function MobileMenu({ displayName }: { displayName: string }) {
  const pathname = usePathname();
  return (
    <Sheet>
      <SheetTrigger asChild>
        <Button variant="ghost" size="icon" className="size-11 lg:hidden" aria-label="Open menu">
          <Menu aria-hidden="true" className="size-5" />
        </Button>
      </SheetTrigger>
      <SheetContent side="left" className="w-72 max-w-[85vw] gap-0 p-0">
        <div className="flex items-center gap-3 border-b border-hairline p-5 pr-12">
          <Avatar className="size-9">
            <AvatarFallback className="bg-surface-strong text-sm text-ink">{displayName.slice(0, 1).toUpperCase()}</AvatarFallback>
          </Avatar>
          <div className="min-w-0">
            <SheetTitle className="truncate text-base text-ink">{displayName}</SheetTitle>
            <SheetDescription className="text-xs text-muted-ink">Your Seed menu</SheetDescription>
          </div>
        </div>
        <nav aria-label="Primary" className="flex-1 overflow-y-auto p-3">
          <ul className="flex flex-col gap-1">
            {NAV_ITEMS.map((item) => {
              const current = isActive(item, pathname);
              const Icon = item.icon;
              return (
                <li key={item.href}>
                  <SheetClose asChild>
                    <Link
                      href={item.href}
                      aria-current={current ? "page" : undefined}
                      className={cn(
                        "flex min-h-11 items-center gap-3 rounded-xl px-3 text-[15px] font-medium transition-colors outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50",
                        current ? "bg-surface-strong text-ink" : "text-body hover:bg-surface-strong/60 hover:text-ink",
                      )}
                    >
                      <Icon aria-hidden="true" className="size-4" />
                      {item.label}
                    </Link>
                  </SheetClose>
                </li>
              );
            })}
          </ul>
        </nav>
        <form action={signOut} className="border-t border-hairline p-3">
          <Button type="submit" variant="ghost" className="h-11 w-full justify-start gap-3 rounded-xl px-3">
            <LogOut aria-hidden="true" />
            Sign out
          </Button>
        </form>
      </SheetContent>
    </Sheet>
  );
}
