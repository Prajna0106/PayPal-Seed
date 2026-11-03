"use server";

import { isProblem, unwrap } from "@paypal-seed/api-client";
import { revalidatePath } from "next/cache";
import { unstable_rethrow } from "next/navigation";
import { z } from "zod";
import { staffApi } from "@/server/api";

export interface CreateStaffState {
  error?: string;
  created?: string;
}

export interface ChangeStaffState {
  error?: string;
  done?: string;
}

const roles = z.array(z.enum(["OFFICER", "CHECKER", "ADMIN"])).min(1);
const memberId = z.union([z.uuid(), z.literal("")]);

const form = z.object({
  email: z.email().max(254),
  displayName: z.string().trim().min(1).max(80),
  roles,
  memberId,
  password: z.string().min(12).max(128),
});

const accessForm = z.object({ staffId: z.uuid(), roles, active: z.boolean(), memberId, linkedBefore: z.boolean() });
const resetForm = z.object({ staffId: z.uuid(), password: z.string().min(12).max(128) });

const PROBLEMS: Record<string, string> = {
  "staff-email-taken": "Someone already has this email.",
  "member-already-linked": "Another staff login is already linked to this member account.",
  "member-not-found": "There's no member with that ID.",
  "staff-self-lockout": "You can't remove your own admin role or suspend yourself.",
  "staff-last-admin": "The desk needs at least one active admin.",
  "staff-not-found": "This person is no longer in the directory.",
  "staff-account-required": "This person has no password to reset, such as a demo persona.",
};

function problemText(error: unknown, fallback: string): string {
  for (const [code, text] of Object.entries(PROBLEMS)) {
    if (isProblem(error, code)) {
      return text;
    }
  }
  return isProblem(error) && error.status === 403 ? "Only admins can do this." : fallback;
}

export async function createStaff(_previous: CreateStaffState, formData: FormData): Promise<CreateStaffState> {
  const parsed = form.safeParse({
    email: String(formData.get("email") ?? "").trim(),
    displayName: String(formData.get("displayName") ?? ""),
    roles: formData.getAll("roles"),
    memberId: String(formData.get("memberId") ?? "").trim(),
    password: String(formData.get("password") ?? ""),
  });
  if (!parsed.success) {
    return { error: "Add a name, a valid email, at least one role and a password of 12 or more characters. A member ID must be a full ID." };
  }
  const { memberId: member, ...rest } = parsed.data;
  try {
    const api = await staffApi();
    await unwrap(api.POST("/api/v1/desk/staff", { body: member ? { ...rest, memberId: member } : rest }));
  } catch (error) {
    unstable_rethrow(error);
    return { error: problemText(error, "We couldn't add this person. Please try again.") };
  }
  revalidatePath("/staff");
  return { created: parsed.data.displayName };
}

export async function changeStaffAccess(_previous: ChangeStaffState, formData: FormData): Promise<ChangeStaffState> {
  const parsed = accessForm.safeParse({
    staffId: formData.get("staffId"),
    roles: formData.getAll("roles"),
    active: formData.get("active") === "on",
    memberId: String(formData.get("memberId") ?? "").trim(),
    linkedBefore: formData.get("linkedBefore") === "true",
  });
  if (!parsed.success) {
    return { error: "Choose at least one role. A member ID must be a full ID." };
  }
  const { staffId, roles: chosen, active, memberId: member, linkedBefore } = parsed.data;
  const link = member ? { linkMemberId: member } : linkedBefore ? { unlinkMember: true } : {};
  try {
    const api = await staffApi();
    await unwrap(api.PATCH("/api/v1/desk/staff/{staffId}", { params: { path: { staffId } }, body: { roles: chosen, active, ...link } }));
  } catch (error) {
    unstable_rethrow(error);
    return { error: problemText(error, "We couldn't save this. Please try again.") };
  }
  revalidatePath("/staff");
  revalidatePath(`/staff/${staffId}`);
  return { done: "Saved." };
}

export async function resetStaffPassword(_previous: ChangeStaffState, formData: FormData): Promise<ChangeStaffState> {
  const parsed = resetForm.safeParse({ staffId: formData.get("staffId"), password: String(formData.get("password") ?? "") });
  if (!parsed.success) {
    return { error: "The starting password needs 12 or more characters." };
  }
  try {
    const api = await staffApi();
    await unwrap(api.POST("/api/v1/desk/staff/{staffId}/password", {
      params: { path: { staffId: parsed.data.staffId } },
      body: { password: parsed.data.password },
    }));
  } catch (error) {
    unstable_rethrow(error);
    return { error: problemText(error, "We couldn't reset the password. Please try again.") };
  }
  revalidatePath(`/staff/${parsed.data.staffId}`);
  return { done: "Done. They're logged out everywhere and must choose their own password next time." };
}
