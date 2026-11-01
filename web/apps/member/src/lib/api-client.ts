const BACKEND_URL = process.env.BACKEND_URL ?? "http://127.0.0.1:8080";

/** Calls the Java backend from this server. `credential` is a session token or the service token. */
export function backend(path: string, credential: string, init: RequestInit = {}): Promise<Response> {
  return fetch(`${BACKEND_URL}${path}`, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${credential}` },
    cache: "no-store",
    signal: AbortSignal.timeout(15_000),
  });
}
