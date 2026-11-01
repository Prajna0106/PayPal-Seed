import "server-only";

import { ApiProblem, type HardshipOption, type HardshipView, unwrap, unwrapOrNull } from "@paypal-seed/api-client";
import type { Locale } from "@paypal-seed/i18n";
import { notFound } from "next/navigation";
import { memberApi } from "@/server/api";
import type { OptionLabel, Summary } from "../copy";

export interface OfferOption {
  optionId: string;
  label: OptionLabel;
  tier: "AUTO_POLICY" | "MAKER_CHECKER" | "NONE";
  recommended: boolean;
  sustainable: boolean | null;
  warnings: string[];
  summary: Summary;
}

export interface HardshipModel {
  caseId: string;
  status: string;
  target: { seq: number; dueDate: string; amountCents: number } | null;
  offer: {
    offerId: string;
    revision: number;
    expiresOn: string | null;
    message: { source: "AI" | "TEMPLATE"; text: string } | null;
    options: OfferOption[];
  } | null;
}

export interface HardshipPage {
  hardship: HardshipModel | null;
  /** The latest plan change made through the safety net, shown when there is no open case any more. */
  lastChange: { version: number; createdOn: string } | null;
}

/** The open case and its offer in the member's language, plus the most recent safety-net change to the plan. */
export async function getHardshipPage(loanId: string, locale: Locale): Promise<HardshipPage> {
  const api = await memberApi();
  try {
    const [view, versions] = await Promise.all([
      unwrapOrNull(api.GET("/api/v1/loans/{loanId}/hardship", { params: { path: { loanId }, query: { lang: locale } } })),
      unwrap(api.GET("/api/v1/loans/{loanId}/schedule/versions", { params: { path: { loanId } } })),
    ]);
    const change = [...versions].reverse().find((v) => v.reason?.startsWith("HARDSHIP_"));
    return {
      hardship: view ? toModel(view) : null,
      lastChange: change?.version && change.createdOn ? { version: change.version, createdOn: change.createdOn } : null,
    };
  } catch (error) {
    if (error instanceof ApiProblem && error.status === 404) {
      notFound();
    }
    throw error;
  }
}

function toModel(view: HardshipView): HardshipModel {
  const target = view.target;
  const offer = view.offer;
  return {
    caseId: view.caseId ?? "",
    status: view.status ?? "OFFER_VIEWED",
    target:
      target?.seq !== undefined && target.dueDate && target.amountCents !== undefined
        ? { seq: target.seq, dueDate: target.dueDate, amountCents: target.amountCents }
        : null,
    offer: offer?.offerId
      ? {
          offerId: offer.offerId,
          revision: offer.revision ?? 1,
          expiresOn: offer.expiresOn ?? null,
          message:
            offer.message?.text && offer.message.source
              ? { source: offer.message.source, text: offer.message.text }
              : null,
          options: (offer.options ?? []).flatMap(toOption),
        }
      : null,
  };
}

function toOption(option: HardshipOption): OfferOption[] {
  if (!option.optionId || !option.label) {
    return [];
  }
  return [
    {
      optionId: option.optionId,
      label: option.label,
      tier: option.tier ?? "MAKER_CHECKER",
      recommended: option.recommended ?? false,
      sustainable: option.sustainable ?? null,
      warnings: option.warnings ?? [],
      // The contract types summary values as numbers; newer backends send dates as ISO strings. Both are handled.
      summary: (option.summary ?? {}) as Summary,
    },
  ];
}
