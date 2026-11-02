import type { Schemas } from "@paypal-seed/api-client";

/** The Home summary and its sections, exactly as the contract defines them (ADR 0018). */
export type Home = Schemas["HomeView"];
export type SavingsSection = Schemas["SavingsSection"];
export type LendingSection = Schemas["LendingSection"];
export type CreditRecordSection = Schemas["CreditRecordSection"];
export type HardshipSection = Schemas["HardshipSection"];
export type ActiveLoan = Schemas["ActiveLoanCard"];
export type OpenApplication = Schemas["OpenApplicationCard"];
export type HomeSectionName = Schemas["HomeSectionName"];
export type HomeSectionErrorCode = Schemas["HomeSectionErrorCode"];

/** Why a section is missing, or `null` when it is there. A missing section with no entry counts as "no data". */
export function sectionState(home: Home, name: HomeSectionName): HomeSectionErrorCode | null {
  if (home[name]) {
    return null;
  }
  return home.sectionErrors.find((error) => error.section === name)?.code ?? "no-data";
}
