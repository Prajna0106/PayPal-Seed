import { HandCoins, House, PiggyBank, ScrollText, Settings, ShoppingBag, Wallet, type LucideIcon } from "lucide-react";
import type { Route } from "next";

export interface NavItem {
  href: Route;
  label: string;
  icon: LucideIcon;
  /** Paths that keep this item highlighted, besides `href` itself (for example an application opened from Borrow). */
  alsoActiveOn?: readonly string[];
}

/** Every member route, in the order people reach for them. Routes owned by other screens are linked up front. */
export const NAV_ITEMS: readonly NavItem[] = [
  { href: "/app" as Route, label: "Home", icon: House },
  { href: "/app/savings" as Route, label: "Savings", icon: PiggyBank },
  { href: "/app/apply" as Route, label: "Borrow", icon: HandCoins, alsoActiveOn: ["/app/applications"] },
  { href: "/app/loans" as Route, label: "Loans", icon: Wallet },
  { href: "/app/record" as Route, label: "Record", icon: ScrollText },
  { href: "/app/shop" as Route, label: "Shop", icon: ShoppingBag },
  { href: "/app/settings" as Route, label: "Settings", icon: Settings },
];

function under(pathname: string, base: string): boolean {
  return pathname === base || pathname.startsWith(`${base}/`);
}

/** Home matches only itself; every other item also matches its sub-pages. */
export function isActive(item: NavItem, pathname: string): boolean {
  if (item.href === "/app") {
    return pathname === "/app";
  }
  return under(pathname, item.href) || (item.alsoActiveOn?.some((base) => under(pathname, base)) ?? false);
}
