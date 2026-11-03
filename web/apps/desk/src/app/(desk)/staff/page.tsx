import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Lock } from "lucide-react";
import type { Metadata } from "next";
import { CreateStaffForm } from "@/features/staff/components/create-staff-form";
import { staffDirectory } from "@/features/staff/server/queries";
import { hasRole, requireSession } from "@/server/session";

export const metadata: Metadata = { title: "Staff" };

export default async function StaffPage() {
  const session = await requireSession();
  if (!hasRole(session, "ADMIN")) {
    return (
      <>
        <PageHeader eyebrow="Platform" title="Staff" />
        <EmptyState icon={<Lock />} title="Admins only" description="Ask an admin to add or change staff accounts." />
      </>
    );
  }
  const staff = await staffDirectory();
  return (
    <>
      <PageHeader eyebrow="Platform" title="Staff" description="Who can log in to the desk, and what they can do." />
      <div className="grid max-w-5xl gap-6 lg:grid-cols-2">
        <CreateStaffForm />
        <ul className="flex flex-col divide-y divide-hairline self-start rounded-2xl border border-hairline bg-surface">
          {staff.map((person) => (
            <li key={person.staffId} className="flex flex-col gap-0.5 p-4">
              <span className="font-medium text-ink">
                {person.displayName}
                {person.active ? null : <span className="text-sm font-normal text-muted-ink"> · Suspended</span>}
              </span>
              <span className="text-sm text-muted-ink">
                {person.email ?? "No email"} · {person.roles.join(", ")}
              </span>
            </li>
          ))}
        </ul>
      </div>
    </>
  );
}
