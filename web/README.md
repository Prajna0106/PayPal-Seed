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

4. Open http://localhost:3000/login and choose **Start Ana's story** (shown outside production only). It creates a loan, seeds four lean weeks of
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
- Members log in with PayPal. Staff log in with their email and a password. Admins manage staff on the desk's Staff
  page; `scripts/create-staff.sh` is only for the first admin or a lost password. `proxy.ts` redirects signed-out
  visitors to `/login`, and someone whose password an admin chose to Your login first.
- Each backend call signs a short actor token from the session (ADR 0016). The desk signs staff tokens with its own
  `SEED_STAFF_ACTOR_TOKEN_SECRETS` and logs staff in with its own `SEED_STAFF_SERVICE_TOKEN`, which the member app never
  holds (ADR 0023). Without a secret, local development sends dev headers instead, which only the backend's dev and
  demo profiles read.
- Ana's story, the member id box and the desk personas need no password, so they work only with `SEED_DEMO_LOGIN`
  (on by default outside production, off in production).
- `SEED_INTERNAL_TOKEN` is used only on the server: to finish a login, for demo seeding on the member app and for the
  admin-only operations page on the desk.

To try real logins on this machine, run `bash scripts/dev-member-login.sh`. It needs Java 21 (on `JAVA_HOME` if set,
otherwise on `PATH`), and `PAYPAL_LOGIN_CLIENT_ID`, `PAYPAL_LOGIN_CLIENT_SECRET` and `SEED_INTERNAL_TOKEN` in
`apps/member/.env.local`. Members log in at http://127.0.0.1:3000/login and staff at http://localhost:3001/login.
