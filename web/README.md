# PayPal Seed web

Two Next.js 16 apps in a pnpm + Turborepo workspace. Conventions for people and coding agents live in the root
[`AGENTS.md`](../AGENTS.md); the visual system is described in [`DESIGN.md`](./DESIGN.md).

| App | Path | Port | What it is |
| --- | --- | --- | --- |
| `@paypal-seed/member` | `apps/member` | 3000 | Landing page (`/`), demo sign-in (`/login`), member app (`/app/**`) |
| `@paypal-seed/desk` | `apps/desk` | 3001 | Loan officer desk: overview, hardship queue, agents, operations |

| Package | Purpose |
| --- | --- |
| `@paypal-seed/ui` | Design system: Tailwind v4 tokens, shadcn components, brand components, formatters |
| `@paypal-seed/api-client` | Typed backend client generated from `contracts/openapi` (server-only) |
| `@paypal-seed/timeline` | Bryntum Scheduler Pro repayment timeline |
| `@paypal-seed/i18n` | en/es strings for member-facing text |
| `@paypal-seed/config` | Shared tsconfig and ESLint configuration |

## Run it

1. Start the backend with the demo profiles (see the root `AGENTS.md`): it must listen on `http://localhost:8080`
   with `SPRING_PROFILES_ACTIVE=demo,dev`, so it accepts the demo identity headers and the local internal token.
2. Configure the apps (the defaults already match a local backend):

   ```bash
   cp .env.example apps/member/.env.local
   cp .env.example apps/desk/.env.local
   ```

3. Install and run:

   ```bash
   pnpm install
   pnpm dev            # member on :3000, desk on :3001
   ```

4. Open http://localhost:3000/login and choose **Start Ana's story**. It creates a loan, seeds four lean weeks of
   income, moves the demo clock and runs the hardship scan, then signs you in as Ana. Open
   http://localhost:3001/login to act as an officer (Aira), a checker (Priyam) or an admin (Prajna).

## Checks

```bash
pnpm typecheck
pnpm lint
pnpm build
pnpm gen:api        # after a contract change: regenerates packages/api-client/src/generated
```

## How the pieces fit

- The browser never calls the backend. Pages and Server Actions call it from the server through
  `@paypal-seed/api-client`, with the identity taken from a signed, httpOnly session cookie.
- Sessions are demo sign-ins until the identity feature lands: the member app signs in as a member id, the desk as
  one of three fixed personas. `proxy.ts` redirects signed-out visitors to `/login`.
- `SEED_INTERNAL_TOKEN` is used only on the server: for demo seeding on the member app and for the admin-only
  operations page on the desk.
