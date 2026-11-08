/** Test staff accounts for the demo deployment. Shown on the sign-in page only when SEED_JUDGE_ACCESS is on. */
export const JUDGE_LOGINS = [
  { role: "Officer", does: "Makes the first approval on a loan or a plan change", email: "officer@gmail.com", password: "officer@gmail.com" },
  { role: "Checker", does: "Gives the second approval, as a different person", email: "checker@gmail.com", password: "checker@gmail.com" },
  { role: "Admin", does: "Staff, operations and the AI kill switch", email: "admin@gmail.com", password: "admin@gmail.com" },
] as const;
