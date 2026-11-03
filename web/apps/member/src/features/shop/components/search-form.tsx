import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { Search } from "lucide-react";

/** A plain GET form: the search lives in the URL (`?q=`), so it works without scripts and can be shared or reloaded. */
export function SearchForm({ query }: { query: string }) {
  return (
    <form role="search" action="/app/shop" method="get" className="flex flex-col gap-2 sm:flex-row sm:items-end">
      <div className="flex flex-1 flex-col gap-2">
        <Label htmlFor="shop-q" className="text-ink">
          What are you saving for?
        </Label>
        <Input
          id="shop-q"
          name="q"
          type="search"
          defaultValue={query}
          maxLength={120}
          autoComplete="off"
          placeholder="A laptop, work boots, a sewing machine"
          className="h-11 bg-surface"
        />
      </div>
      <Button type="submit" size="lg" className="h-11">
        <Search aria-hidden="true" />
        Search
      </Button>
    </form>
  );
}
