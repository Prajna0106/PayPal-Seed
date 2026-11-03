import { z } from "zod";

export const reviewSchema = z.object({
  flagId: z.uuid(),
  decision: z.enum(["CLEAR", "CONFIRM"], { message: "Choose clear or confirm." }),
  note: z.string().trim().max(1000, "Keep the note under 1,000 characters.").optional(),
  key: z.uuid(),
});
