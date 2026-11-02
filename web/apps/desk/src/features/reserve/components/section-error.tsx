import { Alert, AlertDescription, AlertTitle } from "@paypal-seed/ui/components/alert";
import { CircleAlert } from "lucide-react";

/** A failed read, in plain words. The rest of the page keeps working. */
export function SectionError({ title, message }: { title: string; message: string }) {
  return (
    <Alert variant="destructive">
      <CircleAlert aria-hidden="true" />
      <AlertTitle>{title}</AlertTitle>
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  );
}
