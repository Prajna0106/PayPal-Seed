import { redirect } from "next/navigation";
import { currentMember } from "@/lib/auth";

const ERRORS: Record<string, string> = {
  expired: "That login expired. Please try again.",
  cancelled: "You cancelled the login at PayPal.",
  rejected: "PayPal didn't accept that login. Please try again.",
  identity: "PayPal couldn't share your details. Please log in with a personal PayPal account, not a business one.",
  suspended: "This account is suspended. Please contact support.",
  unavailable: "We can't log you in right now. Please try again in a few minutes.",
};

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  if (await currentMember()) redirect("/");
  const { error } = await searchParams;
  const message = error ? (ERRORS[error] ?? ERRORS.unavailable) : null;

  return (
    <main className="card">
      <h1>Welcome to Seed</h1>
      <p>Save, borrow fairly and build your record.</p>
      {message && <p className="error" role="alert">{message}</p>}
      {/* A plain link on purpose: a prefetch would start a login nobody asked for. */}
      <a className="button" href="/api/auth/paypal/start">Log in with PayPal</a>
    </main>
  );
}
