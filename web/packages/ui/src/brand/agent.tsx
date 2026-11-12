import { FileText, Sparkles } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@paypal-seed/ui/lib/utils";

/** The five agents, by the name people see. One source so every screen says the same thing. */
export const AGENTS = {
  guardian: { name: "Guardian Agent", does: "Offers help before a payment is missed" },
  coach: { name: "Savings Coach Agent", does: "Suggests how much to save" },
  underwriting: { name: "Underwriting Agent", does: "Explains the fair loan check" },
  trust: { name: "Trust Agent", does: "Explains your Seed record" },
  analyst: { name: "Desk Analyst Agent", does: "Writes the morning briefing" },
} as const;

export type AgentKey = keyof typeof AGENTS;

/**
 * Who wrote the words: `ai` the agent, checked against the figures; `shadow` the agent ran but a person
 * saw only the template; `template` fixed sentences, no agent.
 */
export type AgentState = "ai" | "shadow" | "template";

const STATE_LABEL: Record<AgentState, string> = {
  ai: "AI-written",
  shadow: "AI shadow run",
  template: "Template wording",
};

export interface AgentBadgeProps {
  agent: AgentKey;
  state?: AgentState;
  /** Star and "AI" only, for tight spaces such as a table cell. */
  compact?: boolean;
  className?: string;
}

/** A pill with the star and the agent's name. Violet when an agent wrote it, plain grey when a template did. */
export function AgentBadge({ agent, state = "ai", compact = false, className }: AgentBadgeProps) {
  const { name } = AGENTS[agent];
  const written = state !== "template";
  const label = compact ? (written ? "AI" : "Template") : written ? name : `${name} · template`;
  return (
    <span
      className={cn(
        "inline-flex h-6 items-center gap-1.5 rounded-full px-2.5 text-xs font-medium whitespace-nowrap",
        state === "ai" && "bg-agent text-white",
        state === "shadow" && "border border-agent-line bg-agent-soft text-agent",
        state === "template" && "bg-surface-strong text-ink-soft",
        className,
      )}
    >
      {written ? (
        <Sparkles aria-hidden="true" className="size-3.5 fill-current" />
      ) : (
        <FileText aria-hidden="true" className="size-3.5" />
      )}
      {label}
    </span>
  );
}

export interface AgentCardProps {
  agent: AgentKey;
  state?: AgentState;
  /** Replaces the agent's one-line job under its name. */
  description?: ReactNode;
  /** Extra controls on the right of the header, such as a refresh button. */
  action?: ReactNode;
  /** A short line under the content, for example what the figures were checked against. */
  footer?: ReactNode;
  children: ReactNode;
  className?: string;
}

/**
 * The frame for anything an agent wrote. Violet with a star when the agent wrote it, so it stands out on a page of
 * code-computed figures; plain when it fell back to a template. Figures never live in the AI text.
 */
export function AgentCard({ agent, state = "ai", description, action, footer, children, className }: AgentCardProps) {
  const { name, does } = AGENTS[agent];
  const written = state !== "template";
  return (
    <section
      aria-label={`${name}: ${STATE_LABEL[state]}`}
      className={cn(
        "relative overflow-hidden rounded-2xl border",
        written ? "border-agent-line bg-agent-soft" : "border-hairline bg-surface",
        className,
      )}
    >
      {written ? (
        <div
          aria-hidden="true"
          className="h-1 bg-gradient-to-r from-gradient-lavender via-gradient-sky to-gradient-mint"
        />
      ) : null}
      <div className="flex flex-col gap-4 p-5">
        <header className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex items-center gap-3">
            <span
              aria-hidden="true"
              className={cn(
                "flex size-9 shrink-0 items-center justify-center rounded-full",
                written ? "bg-agent text-white" : "bg-surface-strong text-muted-ink",
              )}
            >
              {written ? <Sparkles className="size-4 fill-current" /> : <FileText className="size-4" />}
            </span>
            <div className="flex flex-col">
              <h3 className={cn("font-display text-lg leading-tight font-normal", written ? "text-agent" : "text-ink")}>
                {name}
              </h3>
              <p className="text-xs text-muted-ink">{description ?? does}</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <AgentBadge agent={agent} state={state} compact />
            {action}
          </div>
        </header>
        <div className={cn("rounded-xl border bg-surface p-4", written ? "border-agent-line" : "border-hairline")}>
          {children}
        </div>
        {footer ? <p className="text-xs text-muted-ink">{footer}</p> : null}
      </div>
    </section>
  );
}
