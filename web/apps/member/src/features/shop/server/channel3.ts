import "server-only";

import { z } from "zod";
import { env } from "@/server/env";
import { type ShopProduct, safeHttpsUrl } from "../model";

/*
 * Channel3 (product search and commerce API for AI agents). Read from the official docs on 2026-11-02:
 *   https://channel3.ai/docs  (guides: /docs/guides/search.md, /docs/guides/product.md;
 *                              reference: /docs/api-reference/v1/search.md; docs.trychannel3.com redirects here)
 *
 *   Base URL   https://api.trychannel3.com
 *   Auth       header `x-api-key: <CHANNEL3_API_KEY>`   (the SDKs read the same variable name)
 *   Search     POST /v1/search  body { query, limit }
 *              -> { products: Product[], next_page_token: string | null }
 *   Detail     GET  /v1/products/{id}  -> Product
 *   Errors     { "detail": ... } with 400, 401 (bad key), 402 (free credits used up), 422, 500, 504
 *
 *   Product    id, title, description?, brands?: [{ id, name }], images?: [{ url, is_main_image, alt_text? }],
 *              offers?: [{ url, domain, price: { price, compare_at_price?, currency }, availability: InStock | OutOfStock }]
 *   Offers are one retailer's listing each; `offers[].url` is a buy link on buy.trychannel3.com that sends the
 *   shopper to the merchant. Prices are decimal numbers in `price.price`.
 *
 * What the docs did not show: a response example for the whole Product (only partial ones), and no checkout or
 * payment API; nothing there pairs Channel3 with PayPal. So "buy" here means "open the merchant's own checkout link".
 * The key is read here only, on the server, and is never sent to the browser. Every response is parsed with zod
 * and anything that does not fit is dropped, never trusted.
 */

const BASE_URL = "https://api.trychannel3.com";
const TIMEOUT_MS = 6_000;
const SEARCH_LIMIT = 12;

const offerSchema = z.object({
  url: z.string(),
  domain: z.string().min(1).max(120),
  price: z.object({ price: z.number().positive().max(1_000_000), currency: z.string() }),
  availability: z.string().optional(),
});

const productSchema = z.object({
  id: z.string().regex(/^[A-Za-z0-9_-]{1,64}$/),
  title: z.string().min(1).max(300),
  description: z.string().nullish(),
  brands: z.array(z.object({ name: z.string() })).nullish(),
  images: z.array(z.object({ url: z.string(), is_main_image: z.boolean().nullish() })).nullish(),
  offers: z.array(z.unknown()).nullish(),
});

const searchSchema = z.object({ products: z.array(z.unknown()) });

export type Channel3Result<T> = { ok: true; value: T } | { ok: false; reason: "not-configured" | "unavailable" };

export function isChannel3Configured(): boolean {
  return Boolean(env.CHANNEL3_API_KEY?.trim());
}

/** Maps one raw product to ours, or `null` when it has no usable in-stock US-dollar offer. */
function toShopProduct(raw: unknown): ShopProduct | null {
  const product = productSchema.safeParse(raw);
  if (!product.success) {
    return null;
  }
  const offers = (product.data.offers ?? [])
    .map((candidate) => offerSchema.safeParse(candidate))
    .flatMap((parsed) => (parsed.success ? [parsed.data] : []))
    .filter((offer) => offer.price.currency.toUpperCase() === "USD" && offer.availability !== "OutOfStock" && safeHttpsUrl(offer.url));
  const best = offers.sort((a, b) => a.price.price - b.price.price)[0];
  if (!best) {
    return null;
  }
  const images = product.data.images ?? [];
  const image = images.find((candidate) => candidate.is_main_image) ?? images[0];
  return {
    id: product.data.id,
    source: "channel3",
    title: product.data.title,
    brand: product.data.brands?.[0]?.name ?? null,
    description: product.data.description?.slice(0, 600) ?? null,
    // The API sends decimal dollars; cents are made once, here.
    priceCents: Math.round(best.price.price * 100),
    merchant: best.domain,
    merchantUrl: safeHttpsUrl(best.url),
    imageUrl: safeHttpsUrl(image?.url),
    icon: "bag",
  };
}

async function call(path: string, init: { method: "GET" | "POST"; body?: unknown }): Promise<Channel3Result<unknown>> {
  const key = env.CHANNEL3_API_KEY?.trim();
  if (!key) {
    return { ok: false, reason: "not-configured" };
  }
  try {
    const response = await fetch(`${BASE_URL}${path}`, {
      method: init.method,
      headers: { "x-api-key": key, "Content-Type": "application/json", Accept: "application/json" },
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      signal: AbortSignal.timeout(TIMEOUT_MS),
      cache: "no-store",
    });
    if (response.status === 404) {
      return { ok: true, value: null };
    }
    if (!response.ok) {
      console.error("[channel3] request failed", response.status);
      return { ok: false, reason: "unavailable" };
    }
    return { ok: true, value: await response.json() };
  } catch (error) {
    console.error("[channel3] request error", error instanceof Error ? error.name : "unknown");
    return { ok: false, reason: "unavailable" };
  }
}

/** Text search. Products without a usable offer are left out. */
export async function searchChannel3(query: string): Promise<Channel3Result<ShopProduct[]>> {
  const result = await call("/v1/search", { method: "POST", body: { query, limit: SEARCH_LIMIT } });
  if (!result.ok) {
    return result;
  }
  const parsed = searchSchema.safeParse(result.value);
  if (!parsed.success) {
    return { ok: false, reason: "unavailable" };
  }
  return { ok: true, value: parsed.data.products.flatMap((raw) => toShopProduct(raw) ?? []) };
}

/** One product by id; `null` value when Channel3 has none usable. */
export async function getChannel3Product(id: string): Promise<Channel3Result<ShopProduct | null>> {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(id)) {
    return { ok: true, value: null };
  }
  const result = await call(`/v1/products/${encodeURIComponent(id)}`, { method: "GET" });
  if (!result.ok) {
    return result;
  }
  return { ok: true, value: toShopProduct(result.value) };
}
