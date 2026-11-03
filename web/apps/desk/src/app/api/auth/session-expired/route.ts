import { redirect } from "next/navigation";
import { staffApiWithoutRedirect } from "@/server/api";
import { endSession, getSession } from "@/server/session";

/**
 * Pages land here when the backend refuses this session, for example because the person was suspended, because a page
 * can't change cookies. It asks the backend again first, so a link to this route from another website can't log anyone
 * out.
 */
export async function GET() {
  const session = await getSession();
  if (session) {
    const { response } = await (await staffApiWithoutRedirect(session)).GET("/api/v1/desk/staff");
    if (response.status !== 401) {
      redirect("/");
    }
  }
  await endSession();
  redirect("/login?ended=1");
}
