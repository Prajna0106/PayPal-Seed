import { Alert, AlertDescription, AlertTitle } from "@paypal-seed/ui/components/alert";
import { Scale } from "lucide-react";

/** The promise behind every flag (ADR 0019), shown wherever one is. */
export function AdvisoryNote() {
  return (
    <Alert>
      <Scale aria-hidden="true" />
      <AlertTitle>Flags are advice, never a block</AlertTitle>
      <AlertDescription>
        Nothing in Seed refuses, limits or prices anything because of a flag; a person decides. The rules use only what a member did, such as
        goals, payments and applications. No protected attribute, name, email, location or device is used, and every rule can be redone by
        hand from the evidence.
      </AlertDescription>
    </Alert>
  );
}
