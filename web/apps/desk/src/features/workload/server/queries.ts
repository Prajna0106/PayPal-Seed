import "server-only";

import { unwrap } from "@paypal-seed/api-client";
import type { Schemas } from "@paypal-seed/api-client/types";
import { cache } from "react";
import { staffApi } from "@/server/api";

export type Workload = Schemas["Workload"];

/** Approvals waiting per officer role and SLA bucket, as the desk read model counts them. */
export const workload = cache(async (): Promise<Workload> => {
  const api = await staffApi();
  return unwrap(api.GET("/api/v1/desk/workload"));
});
