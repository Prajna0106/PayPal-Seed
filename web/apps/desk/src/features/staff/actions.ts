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

const form = z.object({
  email: z.email().max(254),
  displayName: z.string().trim().min(1).max(80),
  roles: z.array(z.enum(["OFFICER", "CHECKER", "ADMIN"])).min(1),
  password: z.string().min(12).max(128),
});

export async function createStaff(_previous: CreateStaffState, formData: FormData): Promise<CreateStaffState> {
  const parsed = form.safeParse({
    email: String(formData.get("email") ?? "").trim(),
    displayName: String(formData.get("displayName") ?? ""),
    roles: formData.getAll("roles"),
    password: String(formData.get("password") ?? ""),
  });
  if (!parsed.success) {
    return { error: "Add a name, a valid email, at least one role and a password of 12 or more characters." };
  }
  try {
    const api = await staffApi();
    await unwrap(api.POST("/api/v1/desk/staff", { body: parsed.data }));
  } catch (error) {
    unstable_rethrow(error);
    if (isProblem(error, "staff-email-taken")) {
      return { error: "Someone already has this email." };
    }
    return { error: isProblem(error) && error.status === 403 ? "Only admins can add staff." : "We couldn't add this person. Please try again." };
  }
  revalidatePath("/staff");
  return { created: parsed.data.displayName };
}
