import "server-only";

import type { ShopProduct } from "../model";

/**
 * Shown only when no Channel3 key is configured, always labelled "Demo products" on screen. These are invented
 * examples of things a person might save for: no shop, no real brand, no checkout link.
 */
const DEMO: readonly ShopProduct[] = [
  {
    id: "demo-laptop",
    source: "demo",
    title: "Everyday laptop for school",
    brand: "Northfield",
    description: "A light 14 inch laptop for homework, classes and video calls.",
    priceCents: 42900,
    merchant: "Demo shop",
    merchantUrl: null,
    imageUrl: null,
    icon: "laptop",
  },
  {
    id: "demo-boots",
    source: "demo",
    title: "Steel-toe work boots",
    brand: "Ridgeline",
    description: "Hard-wearing, water-resistant boots for a job on your feet.",
    priceCents: 11900,
    merchant: "Demo shop",
    merchantUrl: null,
    imageUrl: null,
    icon: "boots",
  },
  {
    id: "demo-sewing",
    source: "demo",
    title: "Home sewing machine",
    brand: "Stitchwell",
    description: "A sturdy machine for mending, tailoring and a small sewing business.",
    priceCents: 15900,
    merchant: "Demo shop",
    merchantUrl: null,
    imageUrl: null,
    icon: "sewing",
  },
  {
    id: "demo-phone",
    source: "demo",
    title: "Smartphone, 128 GB",
    brand: "Lumen",
    description: "A reliable phone for work, banking and staying in touch.",
    priceCents: 27900,
    merchant: "Demo shop",
    merchantUrl: null,
    imageUrl: null,
    icon: "phone",
  },
];

export function demoProducts(query: string): ShopProduct[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) {
    return [...DEMO];
  }
  return DEMO.filter((product) => {
    const haystack = `${product.title} ${product.brand ?? ""} ${product.description ?? ""}`.toLowerCase();
    return words.every((word) => haystack.includes(word));
  });
}

export function demoProduct(id: string): ShopProduct | null {
  return DEMO.find((product) => product.id === id) ?? null;
}
