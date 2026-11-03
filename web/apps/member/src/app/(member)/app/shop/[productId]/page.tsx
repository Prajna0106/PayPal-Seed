import { Money } from "@paypal-seed/ui/brand/money";
import { Button } from "@paypal-seed/ui/components/button";
import { ArrowLeft } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ProductVisual } from "@/features/shop/components/product-visual";
import { SaveForThis } from "@/features/shop/components/save-for-this";
import { planOptions } from "@/features/shop/model";
import { getProduct } from "@/features/shop/server/catalog";

export const metadata: Metadata = { title: "Save for something" };

export default async function ProductPage({ params }: PageProps<"/app/shop/[productId]">) {
  const { productId } = await params;
  const result = await getProduct(productId);
  if (!result.ok && result.reason === "not-found") {
    notFound();
  }

  const back = (
    <Button asChild variant="ghost" size="sm" className="-ml-3 self-start">
      <Link href={"/app/shop" as Route}>
        <ArrowLeft aria-hidden="true" />
        Back to the list
      </Link>
    </Button>
  );

  if (!result.ok) {
    return (
      <div className="flex flex-col gap-6">
        {back}
        <p role="alert" className="rounded-2xl border border-hairline bg-warning-soft p-5 text-sm text-ink">
          We could not reach the shop just now. Please try again in a moment.
        </p>
      </div>
    );
  }

  const { product } = result;
  const options = planOptions(product.priceCents);

  return (
    <div className="flex flex-col gap-8">
      {back}
      <div className="grid gap-8 md:grid-cols-[minmax(0,320px)_1fr]">
        <ProductVisual product={product} className="w-full max-w-xs" />
        <div className="flex flex-col gap-6">
          <header className="flex flex-col gap-2">
            {product.source === "demo" ? <p className="text-eyebrow text-muted-ink">Demo product</p> : product.brand ? <p className="text-eyebrow text-muted-ink">{product.brand}</p> : null}
            <h1 className="text-display-lg text-ink text-balance">{product.title}</h1>
            <p className="text-sm text-body">
              {product.source === "demo" ? "Example item, not for sale" : `Sold by ${product.merchant}`}
            </p>
            <Money cents={product.priceCents} className="text-display-md text-ink" />
          </header>
          {product.description ? <p className="max-w-prose text-body text-pretty">{product.description}</p> : null}
          <SaveForThis productId={product.id} options={options} attemptKey={crypto.randomUUID()} />
        </div>
      </div>
    </div>
  );
}
