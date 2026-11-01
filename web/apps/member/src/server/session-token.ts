import "server-only";

/**
 * Signed session tokens: `base64url(json) + "." + base64url(HMAC-SHA256(json))`. Pure Web Crypto, so the same code
 * runs in `proxy.ts` and in server components. Verification uses `crypto.subtle.verify`, which compares in
 * constant time.
 */

const encoder = new TextEncoder();
const keys = new Map<string, Promise<CryptoKey>>();

function hmacKey(secret: string): Promise<CryptoKey> {
  let key = keys.get(secret);
  if (!key) {
    key = crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, [
      "sign",
      "verify",
    ]);
    keys.set(secret, key);
  }
  return key;
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(value: string): Uint8Array<ArrayBuffer> | null {
  try {
    const binary = atob(value.replace(/-/g, "+").replace(/_/g, "/"));
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  } catch {
    return null;
  }
}

export async function signToken(payload: unknown, secret: string): Promise<string> {
  const body = encoder.encode(JSON.stringify(payload));
  const signature = new Uint8Array(await crypto.subtle.sign("HMAC", await hmacKey(secret), body));
  return `${toBase64Url(body)}.${toBase64Url(signature)}`;
}

/** Returns the parsed payload, or `null` when the token is malformed or the signature does not match. */
export async function verifyToken(token: string | undefined, secret: string): Promise<unknown> {
  if (!token) {
    return null;
  }
  const [bodyPart, signaturePart, extra] = token.split(".");
  if (!bodyPart || !signaturePart || extra !== undefined) {
    return null;
  }
  const body = fromBase64Url(bodyPart);
  const signature = fromBase64Url(signaturePart);
  if (!body || !signature) {
    return null;
  }
  const valid = await crypto.subtle.verify("HMAC", await hmacKey(secret), signature, body);
  if (!valid) {
    return null;
  }
  try {
    return JSON.parse(new TextDecoder().decode(body)) as unknown;
  } catch {
    return null;
  }
}
