"use client";

import { useEffect } from "react";

/**
 * Clearing the cookie needs a real page load: a redirect from a Server Action stays inside the client router, which
 * can't follow a route handler that changes cookies.
 */
export function LeaveSession() {
  useEffect(() => {
    window.location.replace("/api/auth/session-expired");
  }, []);
  return (
    <p role="status" className="p-8 text-sm text-body">
      Logging you out…
    </p>
  );
}
