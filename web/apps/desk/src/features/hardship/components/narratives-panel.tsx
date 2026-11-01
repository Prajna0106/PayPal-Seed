import type { HardshipCaseDetail } from "@paypal-seed/api-client/types";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@paypal-seed/ui/components/tabs";
import { formatDateTime } from "@paypal-seed/ui/lib/format";
import { Bot, FileText, ShieldCheck, ShieldX } from "lucide-react";

type Narrative = NonNullable<HardshipCaseDetail["narratives"]>[number];

/** The newest text per audience and locale; older ones stay in the API for audit. */
function latest(narratives: Narrative[], audience: "MEMBER" | "OFFICER", locale: string): Narrative | undefined {
  return narratives
    .filter((n) => n.audience === audience && n.locale === locale)
    .sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""))[0];
}

function SourceBadges({ narrative }: { narrative: Narrative }) {
  const ai = narrative.source === "AI";
  const shadow = narrative.source === "AI_SHADOW";
  const grounding = narrative.grounding ?? "NOT_RUN";
  return (
    <div className="flex flex-wrap items-center gap-2">
      <StatusPill tone={ai ? "ink" : shadow ? "info" : "neutral"} dot={false}>
        <span className="inline-flex items-center gap-1 [&_svg]:size-3.5">
          {ai || shadow ? <Bot aria-hidden="true" /> : <FileText aria-hidden="true" />}
          {ai ? "Written by the Guardian" : shadow ? "Guardian (shadow, not sent)" : "Template"}
        </span>
      </StatusPill>
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
    return <p className="text-sm text-muted-ink">No text yet. The outreach goes out once the Guardian answers or its deadline passes.</p>;
  }
  return (
    <div className="flex flex-col gap-3">
      <SourceBadges narrative={narrative} />
      <p className="whitespace-pre-line text-[15px] leading-relaxed text-body">{narrative.text}</p>
    </div>
  );
}

/** The officer brief and the member message, each labelled with who wrote it and whether its numbers checked out. */
export function NarrativesPanel({ narratives }: { narratives: Narrative[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Brief and message</CardTitle>
        <CardDescription>
          Numbers come from code; words may come from the Guardian. Its text is shown only after every figure in it was
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
