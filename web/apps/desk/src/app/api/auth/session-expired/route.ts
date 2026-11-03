import { redirect } from "next/navigation";
import { staffApiWithoutRedirect } from "@/server/api";
import { endSession, getSession, startSession } from "@/server/session";

/**
 * Pages land here when the backend refuses this session, or the person has no roles left, because a page can't change
 * cookies. It asks the backend again first, so a link to this route from another website can't log anyone out.
 */
export async function GET() {
  const session = await getSession();
  if (session) {
    const { data, response } = await (await staffApiWithoutRedirect(session)).GET("/api/v1/auth/staff/me");
    if (data?.passwordChangeRequired) {
      await startSession({ ...session, passwordChangeRequired: true });
      redirect("/account");
    }
    if (response.ok && data && data.roles.length > 0) {
      redirect("/");
    }
  }
  await endSession();
  redirect("/login?ended=1");
}
