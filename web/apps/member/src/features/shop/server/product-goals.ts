import "server-only";

import { cookies } from "next/headers";
import { z } from "zod";
import { env } from "@/server/env";
import { signToken, verifyToken } from "@/server/session-token";
import { type ShopProduct, safeHttpsUrl } from "../model";

/**
 * Which savings goals were made for which product. The backend goal has only a name, so there is nowhere to keep the
 * product id or the merchant link; this signed, httpOnly cookie (`seed_shop_goals`) holds that small list instead. It is
 * a convenience, not a record: the money, progress and status always come from the backend goal, a goal the backend no
 * longer lists is simply not shown, and a cookie from another member is ignored. Kept small (5 entries, links over 200
 * characters left out and fetched again when needed) to stay well under the 4 KB cookie limit.
 */
export const SHOP_GOALS_COOKIE = "seed_shop_goals";
const MAX_ENTRIES = 5;
const MAX_AGE_SECONDS = 60 * 60 * 24 * 90;

const entrySchema = z.object({
  goalId: z.uuid(),
  productId: z.string().max(64),
  source: z.enum(["channel3", "demo"]),
  title: z.string().max(80),
  priceCents: z.number().int().positive(),
  merchant: z.string().max(120),
  merchantUrl: z.string().max(200).nullable(),
});

const payloadSchema = z.object({ memberId: z.uuid(), entries: z.array(entrySchema).max(MAX_ENTRIES) });

export type ShopGoalEntry = z.infer<typeof entrySchema>;

export async function readShopGoals(memberId: string): Promise<ShopGoalEntry[]> {
  const store = await cookies();
  const payload = payloadSchema.safeParse(await verifyToken(store.get(SHOP_GOALS_COOKIE)?.value, env.SESSION_SECRET));
  return payload.success && payload.data.memberId === memberId ? payload.data.entries : [];
}

/** Server Actions only: cookies cannot be set while rendering. Newest first; the oldest fall off. */
export async function rememberShopGoal(memberId: string, goalId: string, product: ShopProduct): Promise<void> {
  const existing = await readShopGoals(memberId);
  const entry: ShopGoalEntry = {
    goalId,
    productId: product.id,
    source: product.source,
    title: product.title.slice(0, 80),
    priceCents: product.priceCents,
    merchant: product.merchant.slice(0, 120),
    merchantUrl: safeHttpsUrl(product.merchantUrl, 200),
  };
  const entries = [entry, ...existing.filter((other) => other.goalId !== goalId)].slice(0, MAX_ENTRIES);
  const store = await cookies();
  store.set(SHOP_GOALS_COOKIE, await signToken({ memberId, entries }, env.SESSION_SECRET), {
    httpOnly: true,
    sameSite: "lax",
    secure: env.isProduction,
    path: "/app/shop",
    maxAge: MAX_AGE_SECONDS,
  });
}
