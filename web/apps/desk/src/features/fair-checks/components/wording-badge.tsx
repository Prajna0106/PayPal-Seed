import { AgentBadge } from "@paypal-seed/ui/brand/agent";

/** Says who wrote the sentences on a fair check: the Underwriting Agent or the fixed templates. */
export function WordingBadge({ modelGenerated }: { modelGenerated: boolean }) {
  return <AgentBadge agent="underwriting" state={modelGenerated ? "ai" : "template"} />;
}
