"use client";

import { Wordmark } from "@paypal-seed/ui/brand/wordmark";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarRail,
} from "@paypal-seed/ui/components/sidebar";
import { Bot, LayoutDashboard, LifeBuoy, ServerCog } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

interface NavItem {
  href: Route;
  label: string;
  icon: ReactNode;
}

const WORK: NavItem[] = [
  { href: "/", label: "Overview", icon: <LayoutDashboard /> },
  { href: "/hardship", label: "Hardship queue", icon: <LifeBuoy /> },
];

const PLATFORM: NavItem[] = [
  { href: "/agents", label: "Agents", icon: <Bot /> },
  { href: "/operations", label: "Operations", icon: <ServerCog /> },
];

function isActive(pathname: string, href: string): boolean {
  return href === "/" ? pathname === "/" : pathname === href || pathname.startsWith(`${href}/`);
}

export function AppSidebar({ footer }: { footer: ReactNode }) {
  const pathname = usePathname();
  const renderGroup = (label: string, items: NavItem[]) => (
    <SidebarGroup>
      <SidebarGroupLabel>{label}</SidebarGroupLabel>
      <SidebarGroupContent>
        <SidebarMenu>
          {items.map((item) => (
            <SidebarMenuItem key={item.href}>
              <SidebarMenuButton asChild isActive={isActive(pathname, item.href)} tooltip={item.label}>
                <Link href={item.href}>
                  {item.icon}
                  <span>{item.label}</span>
                </Link>
              </SidebarMenuButton>
            </SidebarMenuItem>
          ))}
        </SidebarMenu>
      </SidebarGroupContent>
    </SidebarGroup>
  );

  return (
    <Sidebar collapsible="icon" variant="inset">
      <SidebarHeader>
        <Link href="/" className="flex h-10 items-center gap-2 px-2" aria-label="Seed Desk overview">
          <Wordmark className="group-data-[collapsible=icon]:hidden" />
          <Wordmark compact className="hidden group-data-[collapsible=icon]:inline-flex" />
        </Link>
      </SidebarHeader>
      <SidebarContent>
        {renderGroup("Work", WORK)}
        {renderGroup("Platform", PLATFORM)}
      </SidebarContent>
      <SidebarFooter>{footer}</SidebarFooter>
      <SidebarRail />
    </Sidebar>
  );
}
