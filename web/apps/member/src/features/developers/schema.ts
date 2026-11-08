import { z } from "zod";

/** A share token is a URL-safe bearer string; anything else cannot be one. */
export const tokenSchema = z
  .string()
  .trim()
  .min(8, "Paste the whole token.")
  .max(400, "That is too long to be a token.")
  .regex(/^[A-Za-z0-9._~-]+$/, "A token has only letters, numbers and . _ ~ -");
