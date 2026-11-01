"use server";

import { isProblem, problemMessage, unwrap } from "@paypal-seed/api-client";
import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { z } from "zod";
import { memberApi } from "@/server/api";

export type ChooseState =
  | { status: "idle" }
  | { status: "chosen"; optionLabel: string; caseStatus: string; decisionDueAt: string | null; payNowCents: number | null }
  | { status: "stale" }
  | { status: "error"; message: string };

const chooseSchema = z.object({
  loanId: z.uuid(),
  caseId: z.uuid(),
  offerId: z.uuid(),
  optionId: z.uuid(),
  // One key per rendered offer: a retry after a network error reuses it, so the choice is recorded once.
  attemptKey: z.uuid(),
});

/** Records the member's choice. A changed schedule answers 409: the page re-reads and the member confirms again. */
export async function chooseOption(_previous: ChooseState, formData: FormData): Promise<ChooseState> {
  const parsed = chooseSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: "Something was missing from your choice. Please try again." };
  }
  const { loanId, caseId, offerId, optionId, attemptKey } = parsed.data;
  const api = await memberApi();
  try {
    const result = await unwrap(
      api.POST("/api/v1/hardship/cases/{caseId}/choice", {
        params: { path: { caseId }, header: { "Idempotency-Key": `${attemptKey}:${optionId}` } },
        body: { offerId, optionId },
      }),
    );
    revalidatePath(`/app/loans/${loanId}/help`);
    revalidatePath("/app");
    return {
      status: "chosen",
      optionLabel: result.optionLabel ?? "",
      caseStatus: result.status ?? "",
      decisionDueAt: result.decisionDueAt ?? null,
      payNowCents: result.payNowCents ?? null,
    };
  } catch (error) {
    if (isProblem(error, "hardship-offer-stale")) {
      revalidatePath(`/app/loans/${loanId}/help`);
      return { status: "stale" };
    }
    console.error("[hardship] choice failed", error);
    return { status: "error", message: isProblem(error) ? problemMessage(error) : "We could not record your choice. Please try again." };
  }
}

export type DeclineState = { status: "idle" } | { status: "declined" } | { status: "error"; message: string };

const declineSchema = z.object({ loanId: z.uuid(), caseId: z.uuid(), attemptKey: z.uuid() });

export async function declineOffer(_previous: DeclineState, formData: FormData): Promise<DeclineState> {
  const parsed = declineSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: "Please try again." };
  }
  const { loanId, caseId, attemptKey } = parsed.data;
  const api = await memberApi();
  try {
    await unwrap(
      api.POST("/api/v1/hardship/cases/{caseId}/decline", {
        params: { path: { caseId }, header: { "Idempotency-Key": `${attemptKey}:decline` } },
      }),
    );
    revalidatePath(`/app/loans/${loanId}/help`);
    revalidatePath("/app");
    return { status: "declined" };
  } catch (error) {
    console.error("[hardship] decline failed", error);
    return { status: "error", message: isProblem(error) ? problemMessage(error) : "We could not record that. Please try again." };
  }
}

export type AskState = { status: "idle" } | { status: "error"; message: string };

const askSchema = z.object({
  loanId: z.uuid(),
  lang: z.enum(["en", "es"]).default("en"),
  reasonText: z.string().trim().max(500, "Please keep it under 500 characters.").optional(),
});

/** Opens a case at the member's request. The reason is used only to sort the request; it never reaches a prompt. */
export async function askForHelp(_previous: AskState, formData: FormData): Promise<AskState> {
  const parsed = askSchema.safeParse({
    loanId: formData.get("loanId"),
    lang: formData.get("lang") ?? undefined,
    reasonText: formData.get("reasonText") || undefined,
  });
  if (!parsed.success) {
    return { status: "error", message: parsed.error.issues[0]?.message ?? "Please check the form." };
  }
  const { loanId, lang, reasonText } = parsed.data;
  const api = await memberApi();
  try {
    await unwrap(
      api.POST("/api/v1/hardship/cases", {
        params: { query: { lang } },
        body: { loanId, ...(reasonText ? { reasonText } : {}) },
      }),
    );
  } catch (error) {
    console.error("[hardship] ask for help failed", error);
    return { status: "error", message: isProblem(error) ? problemMessage(error) : "We could not send your request. Please try again." };
  }
  revalidatePath("/app");
  redirect(lang === "es" ? `/app/loans/${loanId}/help?lang=es` : `/app/loans/${loanId}/help`);
}
