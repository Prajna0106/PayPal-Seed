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
        {session.passwordChangeRequired ? (
          <p role="status" className="text-body">
            An admin chose your current password. Choose your own before you start; the desk opens once you have.
          </p>
        ) : (
          <p className="text-body">Changing your password logs you out on every other device.</p>
        )}
      </div>
      <ChangePassword />
    </div>
  );
}
