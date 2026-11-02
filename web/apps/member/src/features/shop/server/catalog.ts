import "server-only";

import { cache } from "react";
import type { CatalogResult, ProductResult } from "../model";
import { getChannel3Product, isChannel3Configured, searchChannel3 } from "./channel3";
import { demoProduct, demoProducts } from "./demo-catalog";

/** Search the real catalog when a key is set, the labelled demo list when it is not. An empty query lists demo items only. */
export const searchCatalog = cache(async (rawQuery: string): Promise<CatalogResult> => {
  const query = rawQuery.trim().slice(0, 120);
  if (!isChannel3Configured()) {
    return { ok: true, source: "demo", products: demoProducts(query) };
  }
  if (!query) {
    return { ok: true, source: "channel3", products: [] };
  }
  const result = await searchChannel3(query);
  return result.ok ? { ok: true, source: "channel3", products: result.value } : { ok: false, reason: "unavailable" };
});

/** One product. Demo ids always resolve to the demo list, so a goal saved from a demo product keeps working. */
export const getProduct = cache(async (id: string): Promise<ProductResult> => {
  if (id.startsWith("demo-")) {
    const product = demoProduct(id);
    return product ? { ok: true, product } : { ok: false, reason: "not-found" };
  }
  const result = await getChannel3Product(id);
  if (!result.ok) {
    return { ok: false, reason: result.reason === "not-configured" ? "not-found" : "unavailable" };
  }
  return result.value ? { ok: true, product: result.value } : { ok: false, reason: "not-found" };
});
