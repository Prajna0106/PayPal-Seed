import "server-only";

import { unwrap } from "@paypal-seed/api-client";
import { memberApi } from "@/server/api";
import type { Home } from "../model";

/**
 * The whole Home page in one call. Sections the backend could not build arrive as `null` with a `sectionErrors`
 * entry, so one failing module never blanks the page; only a failed call as a whole throws.
 */
export async function getHome(): Promise<Home> {
  const api = await memberApi();
  return unwrap(api.GET("/api/v1/members/me/home"));
}
