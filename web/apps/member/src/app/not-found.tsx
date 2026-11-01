import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { Button } from "@paypal-seed/ui/components/button";
import Link from "next/link";

export default function NotFound() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-xl items-center px-6">
      <EmptyState
        className="w-full"
        title="We could not find that page"
        description="The link may be old, or the page belongs to another member."
        action={
          <Button asChild>
            <Link href="/">Back to Seed</Link>
          </Button>
        }
      />
    </main>
  );
}
