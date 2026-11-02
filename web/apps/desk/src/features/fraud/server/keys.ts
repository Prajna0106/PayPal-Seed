import "server-only";

/** A fresh idempotency key for one review form: a retry reuses it, the next action gets a new one. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
