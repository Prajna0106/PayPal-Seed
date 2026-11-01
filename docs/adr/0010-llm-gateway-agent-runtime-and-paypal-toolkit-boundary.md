# ADR 0010: LLM Gateway, Agent Runtime and the PayPal Toolkit Boundary

## Status
Proposed (F5). Several vendor details are unverified and listed as spikes below.

## Context
The Guardian needs three things: a way to call language models, a place to run and evaluate the agent, and a position on PayPal's own agent tooling. The hackathon requires that the project "meaningfully use both PayPal and AI", and names Astropods (agent infrastructure) and APIMatic (a PayPal context plugin) among its sponsor tools. The repository already holds an Astropods blueprint, `agents/guardian/blueprint.yaml`, that pins `model: gemini-1.5-pro`.

Constraints that already hold:
- Only the `payments` module holds PayPal write credentials (F4 INV-10). Agents have no write path ([ADR 0003](0003-agent-proposal-governance.md), [ADR 0009](0009-ai-proposes-code-computes-human-approves.md)).
- The backend is Java 21. PayPal's agent toolkit ships for TypeScript (`@paypal/agent-toolkit`, Node 18+) and Python (`paypal-agent-toolkit`, 3.11+). Its MCP server runs locally (`npx -y @paypal/mcp --tools=all`) with an access token in `PAYPAL_ACCESS_TOKEN`. No hosted server is mentioned on the page we could read.
- The toolkit examples cover orders, invoices, subscription plans, shipment, disputes and products. The page's feature text does not mention payouts or Transaction Search. Exact MCP tool names were not on the page.

## Decision
1. **Model access goes through our own `LlmClient` port.** The first adapter calls **OpenRouter**: one OpenAI-compatible endpoint, a model fallback chain, provider routing that opts out of data collection, structured JSON output, and per-call usage and cost. A second adapter for one direct provider is documented as the fallback. The last link in every chain is "no model": deterministic templates.
2. **The agent logic is a thin class behind our ports, run by Astropods.** The Astropods blueprint carries the runtime, the tool wiring and the evals. The same class can run in the `worker` role without Astropods. The blueprint no longer pins one model.
3. **PayPal's Agent Toolkit and MCP server stay out of the money path.** Three uses are allowed:
   - A development and demo harness: run `@paypal/mcp` in Claude Code against the sandbox to explore data and record webhook fixtures, together with the APIMatic Context Plugin.
   - The "agentic checkout" moment for Option D: the Guardian may ask for a catch-up Payment Link or order, through our own `payments` command. The member approves the checkout in PayPal. No agent holds a PayPal tool.
   - Stretch: expose Seed's own read tools and `submit_proposal` as a Seed MCP server in `agent-gateway`, with the same scopes and tokens.
4. **Verification before build.** SH1 confirms Astropods' blueprint format, limits, eval harness and credits. SH2 confirms the PayPal MCP tool names and the sandbox token flow. SH3 confirms OpenRouter's data-collection and zero-retention routing flags, the `models` fallback array, which chosen models support JSON-schema output, and cost reporting. Each has a fallback and none blocks the build.

## Alternatives considered

- **A direct vendor SDK only.** Simplest, with the vendor's newest features such as prompt caching. Rejected as the first choice because one vendor outage, rate limit or deprecation would stop outreach. It stays as the documented second adapter.
- **OpenRouter called directly from the agent, with no port.** Rejected. A port costs one interface and keeps the vendor swappable, testable with a fake, and visible to the architecture tests.
- **Astropods only, with no port of our own.** Rejected. Its public description is a single sentence, so its features are unverified. Our logic must not be locked to it, and the deterministic safety net must run without it.
- **PayPal's Agent Toolkit in the money path.** Rejected. It would give an agent PayPal write tools, which breaks INV-10 and ADR 0003. It is merchant-commerce oriented while our reads come from the ledger and consented Transaction Search. It adds a TypeScript or Python runtime beside a Java adapter, and the MCP server authenticates with a bare access token.
- **A self-hosted open-weights model.** Better data control, but it needs operations work and hardware we do not have before 12 Nov. A later option behind the same port.
- **No gateway and no runtime sponsor.** Possible and honest, but it forfeits the Astropods and Render parts of the demo and gives up the model fallback chain.

## Consequences
- A model, provider or gateway outage degrades the narrative to templates and never stops the feature.
- Data minimisation is enforced in one place: the gateway sends only pseudonymous refs, whole-dollar amounts, dates and signal codes, with provider data collection off.
- Cost and latency are visible per run (`agent_run`), which feeds the budget and the circuit breaker.
- The "PayPal + AI" story rests on our real PayPal integration plus the Guardian. The toolkit appears as a harness and a member-approved checkout, not as the architecture. This is the honest position and it is stated in the README and the sponsor document.
- Three vendor claims must be checked before build (SH1 to SH3). If SH3 shows that data-collection opt-out is not available for a chosen model, that model is removed from the chain.
- One more adapter to maintain, and the fallback adapter must be exercised in CI so it does not rot.
