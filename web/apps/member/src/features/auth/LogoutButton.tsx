"use client";

import { useState } from "react";

export function LogoutButton() {
  const [busy, setBusy] = useState(false);

  async function logOut() {
    setBusy(true);
    await fetch("/api/session", { method: "DELETE" });
    window.location.assign("/login");
  }

  return (
    <button className="button" type="button" disabled={busy} onClick={logOut}>
      Log out
    </button>
  );
}
