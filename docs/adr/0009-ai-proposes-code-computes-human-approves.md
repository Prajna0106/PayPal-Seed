# ADR 0009: AI Proposes, Code Computes, Human Approves

## Status
Proposed (F5). Extends [ADR 0003](0003-agent-proposal-governance.md).

## Context
F5 must spot a likely missed payment early and offer a pause or a smaller, longer plan. The product promise is that "AI spots" and "a human approves". The same system moves a real member's repayment dates and a real PayPal subscription, so three things must be true at once: the numbers are exactly right, a person is accountable for the decision, and the member is never harmed by asking for help.

Language models are good at reading messy text and writing clear explanations. They are not reliable arithmetic engines, they can be steered by hostile text, and they are unavailable from time to time. Detection also has to be explainable to a member, an officer and, in a real deployment, a model-risk reviewer.

## Decision
Three roles, each with a hard boundary.

- **Code computes.** A pure, versioned scorecard in `engines/rules` produces the risk level and reason codes with evidence numbers. The schedule engine produces every option preview. An `OptionRanker` marks the recommended option. Policy caps and approval tiers are code. The same inputs always give the same outputs.
- **AI proposes.** The Guardian agent has read-only tools and one action, `submit_proposal`. It does three things: an escalate-only second opinion (it may raise a borderline case by one level, citing signals present in the tool data, and can never lower a level), a plain-language officer brief, and the member message in English or Spanish. A closed-set extractor classifies a member's free-text reason. The agent never picks the recommended option, never changes a state, and never approves.
- **Humans decide.** The member records a choice. Option A (move a date, 14 days at most) then gets an automatic approval by policy. Options B and C need an officer and a different checker. Separation of duties is enforced in the database.

Between the agent and the humans sits a deterministic **validator**. It recomputes risk, options and every number from source data and runs a **grounding check**: any amount, date or option in the narrative that is absent from the tool results fails the proposal. A failed or missing proposal means the deterministic template is used. The numbers shown to people are identical either way.

The safety net must work with the AI off. Every flow (scan, case, offer, approval, apply) is complete without a model. The AI is an enhancement, never a dependency.

## Alternatives considered

- **A language model as the detector.** "Read the member's transactions and decide who is at risk." Rejected. It is not reproducible, not testable with property tests, hard to explain to a member, sensitive to prompt injection from transaction text, and it would send more personal data to a model than the scorecard needs.
- **An agent with write tools behind a confirmation prompt.** Rejected. A confirmation inside the model's own context is not a control. A prompt injection or a bad generation could confirm itself. Write authority stays in the target module, behind governance.
- **A machine-learning score only, from day one.** Rejected for now. There is no real outcome history to train on, and a black box is a poor first fit for a regulated, member-facing decision. A challenger model can run in shadow later, next to the scorecard.
- **Rules only, with no AI.** Rejected. It would work, but it would miss unstructured evidence (a member's own words), the officer's reading time would be higher, and the hackathon requires that the project meaningfully use AI. The chosen split uses AI where it is strong and keeps it out of arithmetic and approvals.
- **Humans only (officers watch dashboards).** Rejected. It does not scale and it is late. The point is to reach the member before the due date.
- **Automatic approval for every option.** Rejected. A pause or a restructure changes the member's time in debt and the PayPal subscription. A person who is accountable must approve it.

## Consequences
- Detection and numbers are testable with unit and property tests, and the scorecard is auditable reason by reason.
- The agent's blast radius is small: the worst it can do is submit a bad proposal, which the validator rejects or an officer declines.
- Prompt injection cannot reach the schedule. The only path from model text to a state change runs through the validator and a human.
- Escalate-only means the AI can add alerts but can never hide one. A wrongly raised alert costs an unneeded message, which the member can ignore.
- The AI adds measurable value (briefs, outreach, escalations) that can be turned off without losing the feature.
- More moving parts than an agent that "just does it": a validator, a grounding checker, governance and templates must all be built and kept in step. The test plan covers each one.
- Officers can fall into rubber-stamping. The desk records time to decide and flags repeated sub-10-second approvals.
