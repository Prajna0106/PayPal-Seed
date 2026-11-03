import "server-only";

import { unwrap } from "@paypal-seed/api-client";
import { staffApi } from "@/server/api";

export async function staffDirectory() {
  const api = await staffApi();
  return unwrap(api.GET("/api/v1/desk/staff", { params: { query: { includeInactive: true } } }));
}
