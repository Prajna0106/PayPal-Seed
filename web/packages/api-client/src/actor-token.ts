import type { StaffRole } from "./types";

const LIFETIME_SECONDS = 60;
const FIRST_KEY = /^([A-Za-z0-9._-]{1,32}):([^,]{32,})/;
const encoder = new TextEncoder();

export type ActorClaims = { type: "member"; id: string } | { type: "staff"; id: string; roles: readonly StaffRole[] };

function encode(value: unknown): string {
  return bytesToBase64Url(encoder.encode(JSON.stringify(value)));
}

function bytesToBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function isActorTokenSecrets(secrets: string): boolean {
  return FIRST_KEY.test(secrets);
}

export async function signActorToken(secrets: string, actor: ActorClaims): Promise<string> {
  const entry = FIRST_KEY.exec(secrets);
  if (!entry) {
    throw new Error("SEED_ACTOR_TOKEN_SECRETS must start with kid:secret, with 32 or more characters of secret.");
  }
  const [, kid, secret] = entry as unknown as [string, string, string];
  const now = Math.floor(Date.now() / 1000);
  const body = `${encode({ alg: "HS256", typ: "JWT", kid })}.${encode({
    iss: "seed-web",
    aud: "seed-api",
    sub: actor.id,
    act: actor.type,
    ...(actor.type === "staff" ? { roles: actor.roles } : {}),
    iat: now,
    exp: now + LIFETIME_SECONDS,
    jti: crypto.randomUUID(),
  })}`;
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(body));
  return `${body}.${bytesToBase64Url(new Uint8Array(signature))}`;
}
