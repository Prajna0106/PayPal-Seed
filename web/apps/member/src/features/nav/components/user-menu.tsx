import { Avatar, AvatarFallback } from "@paypal-seed/ui/components/avatar";
import { Button } from "@paypal-seed/ui/components/button";
import { LogOut } from "lucide-react";
import { signOut } from "@/features/auth/actions";

/** The member's name and avatar with a sign-out button, for the wide top bar. */
export function UserMenu({ displayName }: { displayName: string }) {
  return (
    <div className="flex items-center gap-3">
      <span className="hidden max-w-40 truncate text-sm text-body xl:inline">{displayName}</span>
      <Avatar className="size-8">
        <AvatarFallback className="bg-surface-strong text-xs text-ink">{displayName.slice(0, 1).toUpperCase()}</AvatarFallback>
      </Avatar>
      <form action={signOut} className="hidden lg:block">
        <Button type="submit" variant="ghost" size="icon" aria-label="Sign out" className="size-11">
          <LogOut aria-hidden="true" />
        </Button>
      </form>
    </div>
  );
}
