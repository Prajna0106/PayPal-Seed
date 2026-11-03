import { cn } from "@paypal-seed/ui/lib/utils";
import { Footprints, Laptop, Scissors, ShoppingBag, Smartphone } from "lucide-react";
import type { ShopProduct } from "../model";

const ICONS = { laptop: Laptop, boots: Footprints, sewing: Scissors, phone: Smartphone, bag: ShoppingBag } as const;

/**
 * A product picture in a fixed square, or an icon when there is none. Channel3 pictures come from many shop hosts, so
 * they cannot go through `next/image` (that needs an allow-list of hosts); a plain lazy `<img>` that sends no referrer
 * is used instead, and the fixed ratio keeps the page from jumping as pictures load.
 */
export function ProductVisual({ product, className }: { product: Pick<ShopProduct, "imageUrl" | "icon" | "title">; className?: string }) {
  const Icon = ICONS[product.icon];
  return (
    <div className={cn("relative flex aspect-square items-center justify-center overflow-hidden rounded-xl bg-surface-strong", className)}>
      {product.imageUrl ? (
        // eslint-disable-next-line @next/next/no-img-element -- remote shop hosts cannot be allow-listed safely
        <img
          src={product.imageUrl}
          alt={product.title}
          referrerPolicy="no-referrer"
          loading="lazy"
          decoding="async"
          className="size-full object-contain"
        />
      ) : (
        <Icon className="size-10 text-muted-ink" aria-hidden="true" />
      )}
    </div>
  );
}
