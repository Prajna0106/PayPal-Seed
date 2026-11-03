import { Money } from "@paypal-seed/ui/brand/money";
import type { Route } from "next";
import Link from "next/link";
import type { ShopProduct } from "../model";
import { ProductVisual } from "./product-visual";

export function ProductCard({ product }: { product: ShopProduct }) {
  return (
    <li>
      <Link
        href={`/app/shop/${encodeURIComponent(product.id)}` as Route}
        className="group flex h-full flex-col gap-4 rounded-2xl border border-hairline bg-surface p-4 outline-none transition-colors hover:border-hairline-strong focus-visible:ring-[3px] focus-visible:ring-ring/50"
      >
        <ProductVisual product={product} />
        <div className="flex flex-1 flex-col gap-1">
          {product.brand ? <p className="text-eyebrow text-muted-ink">{product.brand}</p> : null}
          <h3 className="line-clamp-2 text-base font-medium text-ink group-hover:underline">{product.title}</h3>
          <p className="text-sm text-body">{product.merchant}</p>
        </div>
        <Money cents={product.priceCents} className="text-display-sm text-ink" />
      </Link>
    </li>
  );
}
