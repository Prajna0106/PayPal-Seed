import { Separator } from "@paypal-seed/ui/components/separator";
import { SidebarInset, SidebarProvider, SidebarTrigger } from "@paypal-seed/ui/components/sidebar";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type { ReactNode } from "react";
import { AppSidebar } from "@/features/shell/components/app-sidebar";
import { DeskBreadcrumbs } from "@/features/shell/components/desk-breadcrumbs";
import { PersonaMenu } from "@/features/shell/components/persona-menu";
import { currentAccess } from "@/server/api";
import { PERSONAS } from "@/server/personas";
import { requireSession } from "@/server/session";

/**
 * Staff dashboard shell: collapsible sidebar, header with breadcrumbs, then the page. The sidebar shows the roles the
 * directory gives now, not the ones the session started with.
 */
export default async function DeskLayout({ children }: { children: ReactNode }) {
  const [session, store, access] = await Promise.all([requireSession(), cookies(), currentAccess()]);
  if (access.roles.length === 0 && !session.passwordChangeRequired) {
    redirect("/api/auth/session-expired");
  }
  const sidebarOpen = store.get("sidebar_state")?.value !== "false";
  return (
    <SidebarProvider defaultOpen={sidebarOpen}>
      <AppSidebar
        footer={
          <PersonaMenu
            current={{ staffId: session.staffId, name: session.name, roles: access.roles }}
            personas={session.demo ? PERSONAS : []}
          />
        }
        roles={access.roles}
      />
      <SidebarInset className="bg-canvas">
        <header className="sticky top-0 z-30 flex h-14 shrink-0 items-center gap-2 border-b border-hairline bg-canvas/85 px-4 backdrop-blur-md">
          <SidebarTrigger className="-ml-1" />
          <Separator orientation="vertical" className="mr-2 data-[orientation=vertical]:h-4" />
          <DeskBreadcrumbs />
        </header>
        <main id="main" className="flex flex-1 flex-col gap-8 p-6 lg:p-8">
          {children}
        </main>
      </SidebarInset>
    </SidebarProvider>
  );
}
