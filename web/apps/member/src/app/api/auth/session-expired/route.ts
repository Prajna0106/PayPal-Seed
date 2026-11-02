import { redirect } from "next/navigation";
import { memberApiWithoutRedirect } from "@/server/api";
import { endSession, getSession } from "@/server/session";

/**
 * Pages land here when the backend refuses this session, because a page can't change cookies. It asks the backend
 * again first, so a link to this route from another website can't log anyone out.
 */
export async function GET() {
  const session = await getSession();
  if (session) {
    const { response } = await (await memberApiWithoutRedirect(session.memberId)).GET("/api/v1/members/me");
    if (response.status !== 401) {
      redirect("/app");
    }
  }
  await endSession();
  redirect("/login?error=session-ended");
}
