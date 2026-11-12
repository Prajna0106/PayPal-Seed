import type { HardshipCaseDetail } from "@paypal-seed/api-client/types";
import { AgentBadge, AgentCard } from "@paypal-seed/ui/brand/agent";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@paypal-seed/ui/components/tabs";
import { formatDateTime } from "@paypal-seed/ui/lib/format";
import { ShieldCheck, ShieldX } from "lucide-react";

type Narrative = NonNullable<HardshipCaseDetail["narratives"]>[number];

/** The newest text per audience and locale; older ones stay in the API for audit. */
function latest(narratives: Narrative[], audience: "MEMBER" | "OFFICER", locale: string): Narrative | undefined {
  return narratives
    .filter((n) => n.audience === audience && n.locale === locale)
    .sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""))[0];
}

function agentState(narrative: Narrative): "ai" | "shadow" | "template" {
  return narrative.source === "AI" ? "ai" : narrative.source === "AI_SHADOW" ? "shadow" : "template";
}

function SourceBadges({ narrative }: { narrative: Narrative }) {
  const grounding = narrative.grounding ?? "NOT_RUN";
  return (
    <div className="flex flex-wrap items-center gap-2">
      <AgentBadge agent="guardian" state={agentState(narrative)} />
      {grounding === "PASSED" ? (
        <StatusPill tone="success" dot={false}>
          <span className="inline-flex items-center gap-1 [&_svg]:size-3.5">
            <ShieldCheck aria-hidden="true" />
            Every figure grounded
          </span>
        </StatusPill>
      ) : grounding.startsWith("FAILED") ? (
        <StatusPill tone="danger" dot={false}>
          <span className="inline-flex items-center gap-1 [&_svg]:size-3.5">
            <ShieldX aria-hidden="true" />
            Grounding failed, template used
          </span>
        </StatusPill>
      ) : null}
      {narrative.createdAt ? <span className="text-xs text-muted-ink">{formatDateTime(narrative.createdAt)}</span> : null}
    </div>
  );
}

function Text({ narrative }: { narrative: Narrative | undefined }) {
  if (!narrative) {
    return (
      <p className="text-sm text-muted-ink">
        No text yet. The outreach goes out once the Guardian Agent answers or its deadline passes.
      </p>
    );
  }
  const state = agentState(narrative);
  const body = <p className="whitespace-pre-line text-[15px] leading-relaxed text-body">{narrative.text}</p>;
  if (state === "template") {
    return (
      <div className="flex flex-col gap-3">
        <SourceBadges narrative={narrative} />
        {body}
      </div>
    );
  }
  return (
    <AgentCard
      agent="guardian"
      state={state}
      description={state === "shadow" ? "Shadow run: the member saw the template, not this text." : undefined}
      footer={
        narrative.grounding === "PASSED"
          ? "Figures come from code and were checked against this wording."
          : "Figures come from code, not from this wording."
      }
    >
      <div className="flex flex-col gap-3">
        <SourceBadges narrative={narrative} />
        {body}
      </div>
    </AgentCard>
  );
}

/** The officer brief and the member message, each labelled with who wrote it and whether its numbers checked out. */
export function NarrativesPanel({ narratives }: { narratives: Narrative[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Brief and message</CardTitle>
        <CardDescription>
          Numbers come from code; words may come from the Guardian Agent. Its text is shown only after every figure in it was
          checked against the case.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Tabs defaultValue="officer">
          <TabsList>
            <TabsTrigger value="officer">Officer brief</TabsTrigger>
            <TabsTrigger value="en">Member (English)</TabsTrigger>
            <TabsTrigger value="es">Member (Spanish)</TabsTrigger>
          </TabsList>
          <TabsContent value="officer" className="pt-4">
            <Text narrative={latest(narratives, "OFFICER", "en")} />
          </TabsContent>
          <TabsContent value="en" className="pt-4">
            <Text narrative={latest(narratives, "MEMBER", "en")} />
          </TabsContent>
          <TabsContent value="es" className="pt-4" lang="es">
            <Text narrative={latest(narratives, "MEMBER", "es")} />
          </TabsContent>
        </Tabs>
      </CardContent>
    </Card>
  );
}
