import { z } from "zod";

/** What the browser may send when saving for a product. The price is never in it: the server reads it again. */
export const saveForProductSchema = z.object({
  productId: z.string().regex(/^(demo-[a-z]{1,20}|[A-Za-z0-9_-]{1,64})$/),
  months: z.coerce.number().int().min(1).max(60),
  // One key per rendered form; combined with the chosen plan so a changed choice is a new request, not a 409.
  attemptKey: z.uuid(),
});
