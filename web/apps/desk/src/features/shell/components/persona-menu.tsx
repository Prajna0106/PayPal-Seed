"use client";

import { Avatar, AvatarFallback } from "@paypal-seed/ui/components/avatar";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@paypal-seed/ui/components/dropdown-menu";
import { SidebarMenu, SidebarMenuButton, SidebarMenuItem } from "@paypal-seed/ui/components/sidebar";
import { Check, ChevronsUpDown, LogOut } from "lucide-react";
import { usePathname } from "next/navigation";
import { signInAs, signOut } from "@/features/auth/actions";

export interface PersonaOption {
  id: string;
  name: string;
  title: string;
  roles: readonly string[];
}

export interface PersonaMenuProps {
  current: { staffId: string; name: string; roles: readonly string[] };
  personas: readonly PersonaOption[];
}

/** Switch demo persona without leaving the page: makers and checkers must be different people. */
export function PersonaMenu({ current, personas }: PersonaMenuProps) {
  const pathname = usePathname();
  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <SidebarMenuButton size="lg" className="data-[state=open]:bg-sidebar-accent">
              <Avatar className="size-8">
                <AvatarFallback className="bg-surface-strong text-ink">{current.name.slice(0, 1)}</AvatarFallback>
              </Avatar>
              <span className="grid flex-1 text-left text-sm leading-tight">
                <span className="truncate font-medium">{current.name}</span>
                <span className="truncate text-xs text-muted-ink">{current.roles.join(", ")}</span>
              </span>
              <ChevronsUpDown className="ml-auto size-4" aria-hidden="true" />
            </SidebarMenuButton>
          </DropdownMenuTrigger>
          <DropdownMenuContent side="top" align="start" className="w-64">
            <DropdownMenuLabel>Act as</DropdownMenuLabel>
            {personas.map((persona) => (
              <form key={persona.id} action={signInAs}>
                <input type="hidden" name="personaId" value={persona.id} />
                {/* Stay on this page: a maker hands a case to the checker by switching persona on the case itself. */}
                <input type="hidden" name="next" value={pathname} />
                <DropdownMenuItem asChild>
                  <button type="submit" className="w-full">
                    <span className="flex flex-1 flex-col text-left">
                      <span>{persona.name}</span>
                      <span className="text-xs text-muted-ink">{persona.title}</span>
                    </span>
                    {persona.id === current.staffId ? <Check aria-label="Current" /> : null}
                  </button>
                </DropdownMenuItem>
              </form>
            ))}
            <DropdownMenuSeparator />
            <form action={signOut}>
              <DropdownMenuItem asChild>
                <button type="submit" className="w-full">
                  <LogOut aria-hidden="true" />
                  Sign out
                </button>
              </DropdownMenuItem>
            </form>
          </DropdownMenuContent>
        </DropdownMenu>
      </SidebarMenuItem>
    </SidebarMenu>
  );
}
