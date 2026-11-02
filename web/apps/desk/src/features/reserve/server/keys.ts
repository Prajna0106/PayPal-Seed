import "server-only";

/** A fresh idempotency key for one form: a retry of the same submission reuses it, the next action gets a new one. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
