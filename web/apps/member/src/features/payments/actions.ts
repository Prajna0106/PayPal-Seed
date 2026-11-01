"use server";

import { isProblem, problemMessage, unwrap } from "@paypal-seed/api-client";
import { revalidatePath } from "next/cache";
import { z } from "zod";
import { memberApi } from "@/server/api";
import { isDueNow } from "../loans/model";
import { toPlan } from "../loans/server/queries";

export type PayChoice = "next" | "payoff";

export type CreateOrderResult = { ok: true; orderId: string } | { ok: false; message: string };
export type CaptureResult = { ok: true; amountCents: number } | { ok: false; message: string };

const createSchema = z.object({ loanId: z.uuid(), choice: z.enum(["next", "payoff"]) });
const captureSchema = z.object({ loanId: z.uuid(), orderId: z.string().min(1).max(64) });

/**
 * Creates a PayPal order on the backend for the next payment or the whole balance. The amount is read from the
 * backend here, never taken from the browser, and every click gets its own idempotency key.
 */
export async function createPaymentOrder(input: { loanId: string; choice: PayChoice }): Promise<CreateOrderResult> {
  const parsed = createSchema.safeParse(input);
  if (!parsed.success) {
    return { ok: false, message: "That payment option is not available." };
  }
  const { loanId, choice } = parsed.data;
  const api = await memberApi();
  try {
    const path = { path: { loanId } };
    let amountCents: number;
    let purpose: "CATCH_UP" | "EARLY" | "PAYOFF";
    if (choice === "payoff") {
      amountCents = (await unwrap(api.GET("/api/v1/loans/{loanId}/payoff-quote", { params: path }))).outstandingCents;
      purpose = "PAYOFF";
    } else {
      const next = toPlan(await unwrap(api.GET("/api/v1/loans/{loanId}/schedule", { params: path }))).next;
      if (!next) {
        return { ok: false, message: "There is nothing left to pay on this loan." };
      }
      amountCents = next.remainingCents;
      purpose = isDueNow(next.status) ? "CATCH_UP" : "EARLY";
    }
    if (amountCents <= 0) {
      return { ok: false, message: "There is nothing left to pay on this loan." };
    }
    const order = await unwrap(
      api.POST("/api/v1/loans/{loanId}/payments", {
        params: { ...path, header: { "Idempotency-Key": crypto.randomUUID() } },
        body: { amountCents, purpose },
      }),
    );
    if (!order.orderId) {
      return { ok: false, message: "PayPal did not return an order. Please try again." };
    }
    return { ok: true, orderId: order.orderId };
  } catch (error) {
    console.error("[payments] create order failed", error);
    return { ok: false, message: isProblem(error) ? problemMessage(error) : "We could not start the payment. Please try again." };
  }
}

/** Captures the order the member approved in PayPal; the backend posts it to the loan and the ledger. */
export async function capturePaymentOrder(input: { loanId: string; orderId: string }): Promise<CaptureResult> {
  const parsed = captureSchema.safeParse(input);
  if (!parsed.success) {
    return { ok: false, message: "We could not confirm that payment." };
  }
  const { loanId, orderId } = parsed.data;
  const api = await memberApi();
  try {
    const captured = await unwrap(
      api.POST("/api/v1/loans/{loanId}/payments/{orderId}/capture", { params: { path: { loanId, orderId } } }),
    );
    revalidatePath(`/app/loans/${loanId}`);
    revalidatePath("/app");
    return { ok: true, amountCents: captured.amountCents ?? 0 };
  } catch (error) {
    console.error("[payments] capture failed", error);
    return {
      ok: false,
      message: isProblem(error) ? problemMessage(error) : "PayPal approved the payment but we could not confirm it yet. It will show up shortly.",
    };
  }
}
