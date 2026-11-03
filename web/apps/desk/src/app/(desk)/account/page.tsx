import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import type { Metadata } from "next";
import { ChangePassword } from "@/features/auth/components/change-password";
import { requireSession } from "@/server/session";

export const metadata: Metadata = { title: "Your login" };

export default async function AccountPage() {
  const session = await requireSession();
  if (session.demo) {
    return (
      <EmptyState
        title="This is a demo persona"
        description="Demo personas have no password. Log out and log in with your work email to change yours."
      />
    );
  }
  return (
    <div className="flex max-w-2xl flex-col gap-6">
      <div className="flex flex-col gap-2">
        <h1 className="text-display-md text-ink">Your login</h1>
        <p className="text-body">If someone else set your password, change it here so that only you know it.</p>
      </div>
      <ChangePassword />
    </div>
  );
}
