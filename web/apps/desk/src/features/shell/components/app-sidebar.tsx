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
import {
  Bot,
  CircleDollarSign,
  FileText,
  GanttChart,
  LayoutDashboard,
  LifeBuoy,
  Lock,
  PieChart,
  Scale,
  ServerCog,
  ShieldAlert,
} from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

type Role = "OFFICER" | "CHECKER" | "ADMIN";

interface NavItem {
  href: Route;
  label: string;
  icon: ReactNode;
  /** The page works only for this role. The link stays, marked, and the page explains the restriction. */
  requires?: Role;
}

interface NavGroup {
  label: string;
  items: NavItem[];
}

const GROUPS: NavGroup[] = [
  { label: "Overview", items: [{ href: "/", label: "Overview", icon: <LayoutDashboard /> }] },
  {
    label: "Queues",
    items: [
      { href: "/applications" as Route, label: "Applications", icon: <FileText /> },
      { href: "/fair-checks" as Route, label: "Fair checks", icon: <Scale /> },
      { href: "/hardship", label: "Hardship", icon: <LifeBuoy /> },
    ],
  },
  {
    label: "Portfolio",
    items: [
      { href: "/portfolio" as Route, label: "Portfolio", icon: <PieChart /> },
      { href: "/workload" as Route, label: "Workload", icon: <GanttChart /> },
    ],
  },
  {
    label: "Safety",
    items: [
      { href: "/reserve" as Route, label: "Reserve", icon: <CircleDollarSign /> },
      { href: "/fraud" as Route, label: "Fraud", icon: <ShieldAlert /> },
    ],
  },
  {
    label: "Platform",
    items: [
      { href: "/agents", label: "Agents", icon: <Bot /> },
      { href: "/operations", label: "Operations", icon: <ServerCog />, requires: "ADMIN" },
    ],
  },
];

function isActive(pathname: string, href: string): boolean {
  return href === "/" ? pathname === "/" : pathname === href || pathname.startsWith(`${href}/`);
}

export function AppSidebar({ footer, roles }: { footer: ReactNode; roles: readonly Role[] }) {
  const pathname = usePathname();
  const renderGroup = ({ label, items }: NavGroup) => (
    <SidebarGroup key={label}>
      <SidebarGroupLabel>{label}</SidebarGroupLabel>
      <SidebarGroupContent>
        <SidebarMenu>
          {items.map((item) => {
            const restricted = item.requires !== undefined && !roles.includes(item.requires);
            return (
              <SidebarMenuItem key={item.href}>
                <SidebarMenuButton
                  asChild
                  isActive={isActive(pathname, item.href)}
                  tooltip={restricted ? `${item.label} (admins only)` : item.label}
                >
                  <Link href={item.href}>
                    {item.icon}
                    <span>{item.label}</span>
                    {restricted ? (
                      <span className="ml-auto text-muted-ink group-data-[collapsible=icon]:hidden">
                        <Lock aria-hidden="true" className="size-3.5" />
                        <span className="sr-only">Admins only</span>
                      </span>
                    ) : null}
                  </Link>
                </SidebarMenuButton>
              </SidebarMenuItem>
            );
          })}
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
      <SidebarContent>{GROUPS.map(renderGroup)}</SidebarContent>
      <SidebarFooter>{footer}</SidebarFooter>
      <SidebarRail />
    </Sidebar>
  );
}
