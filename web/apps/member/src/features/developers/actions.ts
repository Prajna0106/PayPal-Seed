"use server";

import type { VerifiedRecord } from "@/features/record/model";
import { publicApi } from "@/features/record/server/public-api";
import { tokenSchema } from "./schema";

export type TryState =
  | { status: "idle" }
  | { status: "ok"; record: VerifiedRecord }
  | { status: "error"; message: string };

/** Calls the public verify route from the server, so the browser never talks to the backend. The token is never logged. */
export async function tryVerify(_prev: TryState, formData: FormData): Promise<TryState> {
  const parsed = tokenSchema.safeParse(formData.get("token"));
  if (!parsed.success) {
    return { status: "error", message: parsed.error.issues[0]?.message ?? "Check the token." };
  }
  try {
    const { data, response } = await publicApi().GET("/api/v1/records/verify/{token}", {
      params: { path: { token: parsed.data } },
    });
    if (response.ok && data) {
      return { status: "ok", record: data };
    }
    if (response.status === 404) {
      return { status: "error", message: "This link is not valid or has expired. A 404 looks the same for every reason." };
    }
    if (response.status === 429) {
      return { status: "error", message: "Too many requests. Wait a minute and try again." };
    }
    return { status: "error", message: "Seed could not answer just now. Try again shortly." };
  } catch {
    return { status: "error", message: "Seed could not be reached. Try again shortly." };
  }
}
