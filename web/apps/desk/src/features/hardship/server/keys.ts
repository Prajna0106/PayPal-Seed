import "server-only";

/** A fresh idempotency key for one form render: a retry of the same submission reuses it, a new render gets a new one. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
