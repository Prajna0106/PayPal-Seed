import { redirect } from "next/navigation";
import type { ReactNode } from "react";
import { LogoutButton } from "@/features/auth/LogoutButton";
import { currentMember } from "@/lib/auth";

export default async function MemberLayout({ children }: { children: ReactNode }) {
  if (!(await currentMember())) redirect("/login");

  return (
    <>
      <header className="topbar">
        <strong>Seed</strong>
        <LogoutButton />
      </header>
      {children}
    </>
  );
}
