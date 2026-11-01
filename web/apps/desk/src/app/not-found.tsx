import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { Button } from "@paypal-seed/ui/components/button";
import Link from "next/link";

export default function NotFound() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-xl items-center px-6">
      <EmptyState
        className="w-full"
        title="Nothing here"
        description="The case or loan may not exist, or the link is old."
        action={
          <Button asChild>
            <Link href="/">Back to the overview</Link>
          </Button>
        }
      />
    </main>
  );
}
