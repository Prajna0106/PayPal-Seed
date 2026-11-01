import { currentMember } from "@/lib/auth";

export default async function HomePage() {
  const member = await currentMember();

  return (
    <main className="card">
      <h1>{member?.displayName ? `Hello, ${member.displayName}` : "Hello"}</h1>
      <p>You're logged in with PayPal.</p>
    </main>
  );
}
