import "server-only";

import { type Schemas, unwrap } from "@paypal-seed/api-client";
import { memberApi } from "@/server/api";
import { requireSession } from "@/server/session";
import { safeHttpsUrl } from "../model";
import { getProduct } from "./catalog";
import { readShopGoals } from "./product-goals";

export interface ProductGoalModel {
  goalId: string;
  title: string;
  merchant: string;
  isDemo: boolean;
  /** The price when the goal was made. The goal's target never moves. */
  targetCents: number;
  savedCents: number;
  monthlyDollars: number;
  status: Schemas["SavingsGoal"]["status"];
  approvalUrl: string | null;
  reached: boolean;
  /** Merchant checkout link, current when Channel3 still knows the product. `null` for demo products. */
  buyUrl: string | null;
  /** The shop's price now, only when it differs from the goal's target. */
  priceNowCents: number | null;
}

// The savings routes speak decimal dollars; cents are made once here and everything after is integer cents.
const toCents = (dollars: number): number => Math.round(dollars * 100);

/** The member's product goals: the remembered products joined to their live backend goals, read in parallel. */
export async function getProductGoals(): Promise<ProductGoalModel[]> {
  const session = await requireSession();
  const remembered = await readShopGoals(session.memberId);
  if (remembered.length === 0) {
    return [];
  }
  const api = await memberApi();
  const goals = await unwrap(
    api.GET("/api/v1/savings/members/{memberId}/goals", { params: { path: { memberId: session.memberId } } }),
  );
  const byId = new Map(goals.map((goal) => [goal.id, goal]));

  const models = await Promise.all(
    remembered.map(async (entry): Promise<ProductGoalModel | null> => {
      const goal = byId.get(entry.goalId);
      if (!goal) {
        return null;
      }
      const targetCents = toCents(goal.targetAmount);
      const savedCents = toCents(goal.currentBalance);
      const reached = goal.status === "COMPLETED" || (targetCents > 0 && savedCents >= targetCents);
      let buyUrl = safeHttpsUrl(entry.merchantUrl);
      let priceNowCents: number | null = null;
      if (reached && entry.source === "channel3") {
        const current = await getProduct(entry.productId);
        if (current.ok) {
          buyUrl = current.product.merchantUrl ?? buyUrl;
          priceNowCents = current.product.priceCents !== targetCents ? current.product.priceCents : null;
        }
      }
      return {
        goalId: goal.id,
        title: entry.title,
        merchant: entry.merchant,
        isDemo: entry.source === "demo",
        targetCents,
        savedCents,
        monthlyDollars: goal.monthlyAmount,
        status: goal.status,
        approvalUrl: goal.status === "PENDING_APPROVAL" ? safeHttpsUrl(goal.approvalUrl) : null,
        reached,
        buyUrl: entry.source === "demo" ? null : buyUrl,
        priceNowCents,
      };
    }),
  );
  return models.flatMap((model) => model ?? []);
}
