import "server-only";

import { isActorTokenSecrets } from "@paypal-seed/api-client/actor-token";
import { z } from "zod";

const DEV_SESSION_SECRET = "dev-only-session-secret-do-not-use-in-production";

const schema = z.object({
  NODE_ENV: z.enum(["development", "production", "test"]).default("development"),
  SEED_API_URL: z.url().default("http://localhost:8080"),
  SEED_INTERNAL_TOKEN: z.string().min(16).default("local-dev-internal-token"),
  SESSION_SECRET: z.string().min(32).optional(),
  SEED_ACTOR_TOKEN_SECRETS: z.string().refine(isActorTokenSecrets, "must start with kid:secret, with 32 or more characters of secret").optional(),
  SEED_DEMO_LOGIN: z.enum(["true", "false"]).optional(),
});

const parsed = schema.safeParse(process.env);
if (!parsed.success) {
  throw new Error(`Invalid environment: ${z.prettifyError(parsed.error)}`);
}

// `next build` loads server modules to collect page data; the secret is enforced when the server runs, not then.
const isBuild = process.env.NEXT_PHASE === "phase-production-build";

if (!parsed.data.SESSION_SECRET) {
  if (parsed.data.NODE_ENV === "production" && !isBuild) {
    throw new Error("SESSION_SECRET is required in production (32+ random characters).");
  }
  if (!isBuild) {
    console.warn("[env] SESSION_SECRET is not set; using a development-only secret.");
  }
}

if (!parsed.data.SEED_ACTOR_TOKEN_SECRETS && parsed.data.NODE_ENV === "production" && !isBuild) {
  throw new Error("SEED_ACTOR_TOKEN_SECRETS is required in production: the backend accepts nothing else from this app.");
}

/** Validated server environment. Never import this from client components. */
export const env = {
  ...parsed.data,
  SESSION_SECRET: parsed.data.SESSION_SECRET ?? DEV_SESSION_SECRET,
  isProduction: parsed.data.NODE_ENV === "production",
  demoLogin: parsed.data.SEED_DEMO_LOGIN ? parsed.data.SEED_DEMO_LOGIN === "true" : parsed.data.NODE_ENV !== "production",
} as const;
