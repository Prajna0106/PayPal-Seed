"use server";

import type { Route } from "next";
import { redirect } from "next/navigation";
import { z } from "zod";
import { personaById } from "@/server/personas";
import { endSession, startSession } from "@/server/session";

const personaForm = z.object({
  personaId: z.uuid(),
  next: z
    .string()
    .regex(/^\/(?!\/)[\w\-/]*$/, "Only local paths")
    .optional()
    .catch(undefined),
});

/** Signs in (or switches) to one of the fixed demo personas. */
export async function signInAs(formData: FormData): Promise<void> {
  const parsed = personaForm.safeParse({ personaId: formData.get("personaId"), next: formData.get("next") ?? undefined });
  const persona = parsed.success ? personaById(parsed.data.personaId) : undefined;
  if (!parsed.success || !persona) {
    redirect("/login");
  }
  await startSession({ kind: "staff", staffId: persona.id, name: persona.name, roles: [...persona.roles] });
  // `next` was validated above as a local path, so it is safe to treat as a route of this app.
  redirect((parsed.data.next ?? "/") as Route);
}

export async function signOut(): Promise<void> {
  await endSession();
  redirect("/login");
}
