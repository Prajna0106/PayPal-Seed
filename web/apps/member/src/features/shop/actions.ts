"use server";

import { isProblem, unwrap } from "@paypal-seed/api-client";
import { revalidatePath } from "next/cache";
import { unstable_rethrow } from "next/navigation";
import { memberApi } from "@/server/api";
import { requireSession } from "@/server/session";
import { goalNameFor, planFor, safeHttpsUrl } from "./model";
import { saveForProductSchema } from "./schema";
import { getProduct } from "./server/catalog";
import { rememberShopGoal } from "./server/product-goals";

export type SaveState =
  | { status: "idle" }
  | { status: "saved"; goalId: string; approvalUrl: string | null; monthlyDollars: number }
  | { status: "error"; message: string };

/**
 * Starts a savings goal for a product. The price comes from the catalog on the server, not from the browser, and
 * the target is that price in dollars. The member still has to agree to the monthly saving at PayPal (the goal waits
 * in PENDING_APPROVAL until then); nothing is bought.
 */
export async function saveForProduct(_previous: SaveState, formData: FormData): Promise<SaveState> {
  const parsed = saveForProductSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: "Something was missing from your choice. Please try again." };
  }
  const { productId, months, attemptKey } = parsed.data;
  const session = await requireSession();

  const found = await getProduct(productId);
  if (!found.ok) {
    return {
      status: "error",
      message:
        found.reason === "unavailable"
          ? "We could not reach the shop just now. Nothing was saved. Please try again in a moment."
          : "We could not find that item any more.",
    };
  }
  const { product } = found;
  const plan = planFor(product.priceCents, months);
  if (!plan) {
    return { status: "error", message: "The price has changed since you looked. Please look at the options again." };
  }

  const api = await memberApi();
  try {
    const goal = await unwrap(
      api.POST("/api/v1/savings/goals", {
        params: { header: { "Idempotency-Key": `${attemptKey}:${plan.months}` } },
        body: {
          name: goalNameFor(product.title),
          // The savings routes take decimal dollars; this is the one place cents turn into dollars.
          targetAmount: product.priceCents / 100,
          monthlyAmount: plan.monthlyDollars,
          currency: "USD",
        },
      }),
    );
    await rememberShopGoal(session.memberId, goal.id, product);
    revalidatePath("/app/shop/goals");
    revalidatePath("/app/savings");
    revalidatePath("/app");
    return { status: "saved", goalId: goal.id, approvalUrl: safeHttpsUrl(goal.approvalUrl), monthlyDollars: plan.monthlyDollars };
  } catch (error) {
    unstable_rethrow(error);
    console.error("[shop] save for product failed", error);
    if (isProblem(error, "idempotency-key-reused")) {
      return { status: "error", message: "This was already sent with different details. Please reload the page and try again." };
    }
    if (isProblem(error) && error.status === 409) {
      return { status: "error", message: "You already have three savings goals open. Finish or close one, and then you can start another." };
    }
    if (isProblem(error) && error.status === 503) {
      return { status: "error", message: "PayPal is not reachable right now. Nothing was set up. Please try again in a moment." };
    }
    return { status: "error", message: "We could not start this savings goal. Nothing was charged. Please try again." };
  }
}
