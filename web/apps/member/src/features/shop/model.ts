/** What the shop screens know about one product, whichever source it came from. Prices are integer cents. */
export interface ShopProduct {
  /** Channel3's product id, or `demo-...` for the built-in demo list. */
  id: string;
  source: "channel3" | "demo";
  title: string;
  brand: string | null;
  description: string | null;
  priceCents: number;
  merchant: string;
  /** Checkout link at the merchant. Always https; `null` for demo products, which have no shop. */
  merchantUrl: string | null;
  imageUrl: string | null;
  /** Icon shown when there is no picture. */
  icon: ProductIcon;
}

export type ProductIcon = "laptop" | "boots" | "sewing" | "phone" | "bag";

export type CatalogResult =
  | { ok: true; source: "channel3" | "demo"; products: ShopProduct[] }
  | { ok: false; reason: "unavailable" };

export type ProductResult =
  | { ok: true; product: ShopProduct }
  | { ok: false; reason: "not-found" | "unavailable" };

/** The smallest monthly amount the backend accepts for a savings goal (whole dollars). */
export const MIN_MONTHLY_DOLLARS = 5;

export interface PlanOption {
  months: number;
  monthlyDollars: number;
}

/**
 * Three clear ways to spread the price: about 3, 6 or 12 months. The member picks one; the backend still validates the
 * amount. Whole dollars rounded up (PayPal subscriptions need them), never below the backend minimum, and options
 * that would collapse to the same monthly amount are shown once.
 */
export function planOptions(priceCents: number): PlanOption[] {
  const priceDollars = priceCents / 100;
  const seen = new Set<number>();
  const options: PlanOption[] = [];
  for (const target of [3, 6, 12]) {
    const monthlyDollars = Math.max(MIN_MONTHLY_DOLLARS, Math.ceil(priceDollars / target));
    if (seen.has(monthlyDollars)) {
      continue;
    }
    seen.add(monthlyDollars);
    options.push({ months: Math.ceil(priceDollars / monthlyDollars), monthlyDollars });
  }
  return options;
}

/** The option for a chosen number of months, or `undefined` when it is not one we offered. */
export function planFor(priceCents: number, months: number): PlanOption | undefined {
  return planOptions(priceCents).find((option) => option.months === months);
}

/** Goal names are the product name; keep them short enough for any list. */
export function goalNameFor(title: string): string {
  const name = title.trim().replace(/\s+/g, " ");
  return name.length > 80 ? `${name.slice(0, 79).trimEnd()}…` : name;
}

/** Only https links are ever put in an `href` or an `<img>`. */
export function safeHttpsUrl(value: string | null | undefined, maxLength = 2000): string | null {
  if (!value || value.length > maxLength) {
    return null;
  }
  try {
    return new URL(value).protocol === "https:" ? value : null;
  } catch {
    return null;
  }
}
