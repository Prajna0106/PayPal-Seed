import { redirect } from "next/navigation";
import { sessionApi } from "@/server/api";
import { endSession, getSession } from "@/server/session";

/**
 * Pages land here when the backend says a login session has ended, because a page can't change cookies. It asks the
 * backend again first, so a link to this route from another website can't log anyone out.
 */
export async function GET() {
  const session = await getSession();
  if (session?.sessionToken) {
    const { response } = await sessionApi(session.sessionToken).GET("/api/v1/auth/me");
    if (response.ok) {
      redirect("/app");
    }
  }
  await endSession();
  redirect("/login?error=session-ended");
}
