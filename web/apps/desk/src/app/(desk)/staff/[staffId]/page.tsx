import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Lock } from "lucide-react";
import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { ResetPasswordForm } from "@/features/staff/components/reset-password-form";
import { StaffAccessForm } from "@/features/staff/components/staff-access-form";
import { staffDirectory } from "@/features/staff/server/queries";
import { hasRole, requireSession } from "@/server/session";

export const metadata: Metadata = { title: "Manage staff member" };

export default async function StaffMemberPage({ params }: { params: Promise<{ staffId: string }> }) {
  const [{ staffId }, session] = await Promise.all([params, requireSession()]);
  if (!hasRole(session, "ADMIN")) {
    return (
      <>
        <PageHeader eyebrow="Platform" title="Staff" />
        <EmptyState icon={<Lock />} title="Admins only" description="Ask an admin to add or change staff accounts." />
      </>
    );
  }
  const person = (await staffDirectory()).find((entry) => entry.staffId === staffId);
  if (!person) {
    notFound();
  }
  return (
    <>
      <PageHeader eyebrow="Staff" title={person.displayName}
        description={`${person.email ?? "No email"}${person.active ? "" : " · Suspended"}`} />
      <div className="grid max-w-5xl gap-6 lg:grid-cols-2">
        <StaffAccessForm staffId={person.staffId} roles={person.roles} active={person.active}
          linkedMemberId={person.linkedMemberId ?? null} />
        {person.canLogIn ? <ResetPasswordForm staffId={person.staffId} /> : null}
      </div>
    </>
  );
}
