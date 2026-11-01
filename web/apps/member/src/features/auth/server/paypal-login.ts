import "server-only";

import { env } from "@/server/env";

/** Remembers one login attempt between leaving for PayPal and coming back. */
export const LOGIN_COOKIE = "seed_login";

/** Must match a return URL saved on the PayPal app exactly. */
export const PAYPAL_CALLBACK = `${env.APP_ORIGIN}/api/auth/paypal/callback`;
