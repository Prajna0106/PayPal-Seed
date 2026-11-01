"use server";

import { isProblem, problemMessage } from "@paypal-seed/api-client";
import { redirect } from "next/navigation";
import { z } from "zod";
import { sessionApi } from "@/server/api";
import { endSession, getSession, startSession } from "@/server/session";
import { seedAnaStory } from "./server/seed-ana";

export interface SignInState {
  error?: string;
}

/** Seeds a fresh Ana story on the backend and signs this browser in as Ana. */
export async function startAnaStory(_previous: SignInState): Promise<SignInState> {
  let memberId: string;
  try {
    ({ memberId } = await seedAnaStory());
  } catch (error) {
    console.error("[demo] seeding Ana's story failed", error);
    return {
      error: isProblem(error)
        ? problemMessage(error)
        : "We could not reach the Seed backend. Check that it is running on SEED_API_URL.",
    };
  }
  await startSession({ kind: "member", memberId, displayName: "Ana" });
  redirect("/app");
}

const memberIdSchema = z.object({ memberId: z.uuid({ message: "Enter a member id (a UUID)." }) });

export async function signInWithMemberId(_previous: SignInState, formData: FormData): Promise<SignInState> {
  const parsed = memberIdSchema.safeParse({ memberId: String(formData.get("memberId") ?? "").trim() });
  if (!parsed.success) {
    return { error: parsed.error.issues[0]?.message ?? "Enter a member id." };
  }
  await startSession({ kind: "member", memberId: parsed.data.memberId, displayName: "Member" });
  redirect("/app");
}

export async function signOut(): Promise<void> {
  const session = await getSession();
  if (session?.sessionToken) {
    // The cookie goes either way; if this fails, the backend session still ends when it idles out.
    await sessionApi(session.sessionToken)
      .POST("/api/v1/auth/logout")
      .catch((error: unknown) => console.error("[auth] logout did not reach the backend", error));
  }
  await endSession();
  redirect("/");
}
