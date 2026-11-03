import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { Search, ShoppingBag } from "lucide-react";
import { searchCatalog } from "../server/catalog";
import { ProductCard } from "./product-card";

/** The product list for a query. Streams in behind a skeleton so the search box is there at once. */
export async function Results({ query }: { query: string }) {
  const result = await searchCatalog(query);

  if (!result.ok) {
    return (
      <div role="alert" className="rounded-2xl border border-hairline bg-warning-soft p-5 text-sm text-ink">
        We could not reach the product search just now. Nothing is wrong with your savings. Please try again in a moment.
      </div>
    );
  }

  if (result.source === "channel3" && !query) {
    return (
      <EmptyState
        icon={<Search aria-hidden="true" />}
        title="Search for something you would like"
        description="Type what you have in mind above. Take your time: looking costs nothing."
      />
    );
  }

  if (result.products.length === 0) {
    return (
      <EmptyState
        icon={<ShoppingBag aria-hidden="true" />}
        title="Nothing found for that"
        description="Try a shorter or different word, like the kind of thing rather than the brand."
      />
    );
  }

  return (
    <section aria-labelledby="results-heading" className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h2 id="results-heading" className="text-display-sm text-ink">
          {result.source === "demo" ? "Demo products" : query ? `Results for “${query}”` : "Results"}
        </h2>
        {result.source === "demo" ? (
          <p className="text-sm text-body">
            Product search is not switched on here, so these are example items to show how saving for something works. They are not real products and there is no shop behind them.
          </p>
        ) : null}
      </div>
      <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {result.products.map((product) => (
          <ProductCard key={product.id} product={product} />
        ))}
      </ul>
    </section>
  );
}
