/** Response and request shapes from the contracts. Type-only: safe to import anywhere. */
import type { components as SeedComponents, paths as SeedPaths } from "./generated/seed-api";
import type { components as InternalComponents, paths as InternalPaths } from "./generated/internal-jobs";

export type { SeedPaths, InternalPaths };

export type Schemas = SeedComponents["schemas"];
export type InternalSchemas = InternalComponents["schemas"];

export type LoanSummary = Schemas["LoanSummary"];
export type Bucket = Schemas["Bucket"];
export type InstalmentLine = Schemas["InstalmentLine"];
export type ScheduleView = Schemas["ScheduleView"];
export type Slot = Schemas["Slot"];
export type VersionView = Schemas["VersionView"];
export type TransactionView = Schemas["TransactionView"];
export type TimelineView = Schemas["TimelineView"];
export type CheckoutOrder = Schemas["CheckoutOrder"];
export type CapturedOrder = Schemas["CapturedOrder"];
export type MandateView = Schemas["MandateView"];
export type CobRun = Schemas["CobRun"];
export type HardshipCaseStatus = Schemas["HardshipCaseStatus"];
export type HardshipOption = Schemas["HardshipOption"];
export type HardshipView = Schemas["HardshipView"];
export type HardshipChoice = Schemas["HardshipChoice"];
export type HardshipQueueRow = Schemas["HardshipQueueRow"];
export type HardshipCaseDetail = Schemas["HardshipCaseDetail"];
export type KillSwitchState = Schemas["KillSwitchState"];
export type Problem = Schemas["Problem"];

/** Who a request is made as. `session` is a real login; the backend trusts `member` and `staff` only in dev/demo. */
export type SeedIdentity =
  | { kind: "session"; token: string }
  | { kind: "member"; memberId: string }
  | { kind: "staff"; staffId: string; roles: readonly StaffRole[] }
  | { kind: "internal"; token: string }
  | { kind: "anonymous" };

export type StaffRole = "OFFICER" | "CHECKER" | "ADMIN";
