import { LeaveSession } from "@/features/auth/components/leave-session";

/** Where the app goes when the backend refuses this session. See {@link LeaveSession}. */
export default function SessionEndedPage() {
  return <LeaveSession />;
}
