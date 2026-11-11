import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Bot, FileText } from "lucide-react";

/** Says who wrote the sentences on a fair check: the underwriting agent (AI) or the fixed templates. */
export function WordingBadge({ modelGenerated, long = false }: { modelGenerated: boolean; long?: boolean }) {
  return (
    <StatusPill tone={modelGenerated ? "ink" : "neutral"} dot={false}>
      <span className="inline-flex items-center gap-1 [&_svg]:size-3.5">
        {modelGenerated ? <Bot aria-hidden="true" /> : <FileText aria-hidden="true" />}
        {modelGenerated ? (long ? "Written by the underwriting agent (AI)" : "AI wording") : "Template"}
      </span>
    </StatusPill>
  );
}
