import "server-only";

import { type Schemas, unwrapOrNull } from "@paypal-seed/api-client";
import { unstable_rethrow } from "next/navigation";
import { memberApi } from "@/server/api";
import { CONSENT_TYPES, type ConsentTypeName } from "../schema";

export type MemberProfile = Schemas["MemberProfile"];
type ConsentRecord = Schemas["ConsentRecord"];

export interface ConsentView {
  type: ConsentTypeName;
  /** The open line, if the member has agreed and not withdrawn. */
  current: ConsentRecord | null;
  /** Earlier lines, newest first (withdrawn or replaced by a newer version). */
  past: ConsentRecord[];
}

export interface SettingsModel {
  /** `null` for a demo member who has never signed in with PayPal: there is no profile to show yet. */
  profile: MemberProfile | null;
  consents: ConsentView[];
  /** What the fair check reads and never reads, from the backend, for the income consent. */
  income: { used: string[]; neverUsed: string[]; fairCheckConsented: boolean } | null;
}

/** Profile, consent ledger and the fair check's own consent, read in parallel. */
export async function getSettings(): Promise<SettingsModel> {
  const api = await memberApi();
  const [profile, ledger, income] = await Promise.all([
    unwrapOrNull(api.GET("/api/v1/members/me")),
    unwrapOrNull(api.GET("/api/v1/members/me/consents")),
    unwrapOrNull(api.GET("/api/v1/underwriting/consent", {})).catch((error: unknown) => {
      unstable_rethrow(error);
      return null;
    }),
  ]);

  const lines = ledger ?? [];
  return {
    profile,
    consents: CONSENT_TYPES.map((type) => {
      const own = lines.filter((line) => line.type === type);
      return {
        type,
        current: own.find((line) => line.active) ?? null,
        past: own.filter((line) => !line.active),
      };
    }),
    income: income
      ? { used: income.usedData, neverUsed: income.neverUsedData, fairCheckConsented: income.consented }
      : null,
  };
}
