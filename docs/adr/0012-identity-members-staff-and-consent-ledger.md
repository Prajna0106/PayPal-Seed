# ADR 0012: Identity, Members, Staff and the Consent Ledger

## Status
Proposed

## Context
Every module already keys its data by a member id, and the desk already shows staff by fixed ids. Nothing owned those ids. Three things were missing.

1. **Who a member is.** The web app signs members in with PayPal, but the backend only saw an `X-Seed-Member-Id` header. A returning PayPal user has to map to the same member every time, or their goals, loans and record would split across ids.
2. **Who staff are.** Approvals store maker and checker ids. The desk needs their names without each module storing a copy.
3. **What a member agreed to.** Members need one list of "what have I agreed to". The fair loan check already has its own income-read consent in `underwriting`, and that consent gates a data fetch, so it must not be replaced.

## Decision
**Module.** `identity` owns schema `identity` with three tables: `member`, `staff` and `consent`. It has no module dependencies. Other modules use `IdentityFacade`.

**Members.** `member.id` is the Seed member id. `paypal_payer_id` (the `sub` of Log in with PayPal) is unique, so the payer id to member mapping is a database fact. The Next.js server calls `POST /internal/members/login` with the service token after PayPal signs the person in. The call is idempotent on the payer id:

- the member row is locked by payer id; if absent it is inserted with `ON CONFLICT DO NOTHING`, and a loser of that race continues as a later login;
- the first login creates the member and publishes `MemberRegistered`; later logins return the same id and set `last_login_at`;
- a field PayPal did not send never erases a stored value. A language is replaced only when the login carries a supported locale (`en`, `es`; `es-MX` counts as `es`), so a login without a locale never puts a Spanish speaker back on English. The web server should send the locale on first login and leave it out afterwards if the member has chosen a language in their profile.

Language is `en` or `es`; wallet preference is `PAYPAL` or `VENMO` (a stored choice only, Venmo moves no money yet). Both are enforced by `CHECK` constraints as well as in code. The member edits language, wallet and display name through `PATCH /api/v1/members/me`.

**Staff.** `staff.id` is the staff id used by the desk. Roles reuse `Actor.Role` from `platform/security`, limited to `OFFICER`, `CHECKER` and `ADMIN`. `PUT /internal/staff/{staffId}` (service token) creates or replaces a record; `GET /api/v1/desk/staff` lists active staff to any staff role. `IdentityFacade.staffDisplayNames(ids)` resolves many ids in one query for the maker and checker columns, including inactive staff, because an old approval still needs a name. Under the `demo` and `dev` profiles the three demo staff are inserted on start (`...4a01` Aira Jena officer, `...4a02` Priyam Priyanshu Pradhan checker, `...4a03` Prajna Jeet Ojha officer, checker and admin). Seeding only adds missing rows, so a restart never undoes an edit. Roles in this table describe the person; what a request may do is still decided by the `Actor` the request authenticates as, until real staff authentication replaces the dev headers.

**Consent ledger.** `consent` rows carry type (`TERMS`, `PRIVACY`, `INCOME_READ`, `RECORD_SHARING`), version, `granted_at` and `revoked_at`.

- A line is never edited or deleted. The only change the database accepts is setting `revoked_at` once on an open line (a trigger rejects every other `UPDATE`, every `DELETE` and `TRUNCATE`). Agreeing again appends a new line, so the history is complete.
- A partial unique index allows one open line per member and type.
- The same type and version while open is a replay and changes nothing (`200`, no event). A newer version closes the open line and appends a new one.
- A grant must name the current version of the text (`seed.identity.consent.*-version`, default `2026-11`) or omit it; any other version is `422 consent-version-stale`, so nobody agrees to text they were not shown.
- Revoking is always allowed, immediate and idempotent (`204`, also when nothing was open). It publishes `ConsentRevoked` only when a line was closed.

**Identity records, underwriting enforces.** `INCOME_READ` in this ledger is the member-facing record. `underwriting` keeps its own consent, which is the only thing the fair loan check reads, and identity does not call or change it. The two can drift unless underwriting follows identity, so identity publishes `ConsentGranted` and `ConsentRevoked`, and exposes `hasActiveConsent(memberId, type)`. Underwriting should revoke its consent when it receives `ConsentRevoked` for `INCOME_READ`. Until it does, the web app must call both revoke endpoints. The ledger never grants underwriting anything by itself.

**Events.** `MemberRegistered`, `MemberProfileChanged`, `ConsentGranted`, `ConsentRevoked` in `api.events`, published inside the transaction that changed the data. None carries an email, a name or a payer id.

**Personal data.** Email and display name are returned only to their owner (member routes read the id from the actor, never from the URL) and, for staff, to officers. They are not logged: the records that hold them print a masked `toString`, and no log line in the module prints a member or a staff value. The payer id is never returned to a member. The agent gateway's `PiiRedactor` already masks email-shaped text and PayPal-style ids before any model call; it cannot know a person's name, so names must keep staying out of tools and prompts.

**Authorisation.** Member routes require the member actor (staff and the service get `403`). Internal routes require the service actor (`401` without a valid token, `403` for members and staff). The desk list requires `OFFICER`, `CHECKER` or `ADMIN`. A signed-in member id that has no record gets `404 member-not-found` on every member route.

## API

| Route | Who | Notes |
| --- | --- | --- |
| `POST /internal/members/login` | service | body: `paypalPayerId`, `email`, `name`, `verified`, `locale`; returns `{created, member}` |
| `GET /api/v1/members/me` | member | profile; never includes the payer id |
| `PATCH /api/v1/members/me` | member | `language`, `walletPreference`, `displayName`; only the fields sent change |
| `GET /api/v1/members/me/consents` | member | whole ledger, newest first |
| `POST /api/v1/members/me/consents` | member | `{type, version?}`; `201` new, `200` replay |
| `DELETE /api/v1/members/me/consents/{type}` | member | `204` |
| `PUT /internal/staff/{staffId}` | service | `displayName`, `email`, `roles`, `active` |
| `GET /api/v1/desk/staff?includeInactive=` | officer, checker, admin | |

Error `type` values: `member-not-found`, `unsupported-language`, `unknown-wallet-preference`, `unknown-consent-type`, `consent-version-stale`, `invalid-name`, `invalid-payer-id`, `invalid-staff-roles`.

The drafts are in `contracts/openapi/pending/identity.yaml` and `contracts/events/pending/identity.json`. Writes are idempotent by business key (payer id; member, type and version; staff id), so these routes take no `Idempotency-Key`.

## Settings

| Key | Default | Meaning |
| --- | --- | --- |
| `seed.identity.consent.terms-version` | `2026-11` | current version of the terms text |
| `seed.identity.consent.privacy-version` | `2026-11` | privacy notice |
| `seed.identity.consent.income-read-version` | `2026-11` | income-read consent text |
| `seed.identity.consent.record-sharing-version` | `2026-11` | record-sharing consent text |

## Consequences
- One place maps a PayPal payer to a member; the web app needs no mapping table of its own.
- Desk screens can show names for maker and checker with one call.
- A member can see and withdraw everything they agreed to. Withdrawing income read in identity does not by itself stop the fair loan check until underwriting listens to `ConsentRevoked`; this is the one open coupling and is stated above.
- Real staff authentication (SSO) is still pending; the staff table is ready for it, but today roles come from the request headers in dev and demo.
- A member can exist in other modules (demo personas, seeded loans) without an identity record. Their identity routes return `404` until they sign in with PayPal once.

## Alternatives considered
- **Store the payer id in each module that needs it.** Rejected: it splits a person across ids and spreads personal data.
- **Make identity own the income-read consent that underwriting enforces.** Rejected: underwriting is built and tested around its own record, and a consent that gates a data fetch should be enforced where the data is read.
- **Edit consent rows to revoke.** Rejected for the fuller ledger of what was agreed and when. The single permitted closing update keeps the history without a second table.
- **Separate event rows for grant and revoke.** Rejected: one row per agreement with an end time is easier to read for "what is active now" and the trigger keeps it safe.

## Addendum: payer id and payout recipient for other modules
`IdentityFacade.paypalPayerId(memberId)` and `payoutRecipient(memberId)` (a `PayoutRecipient(payerId, email)` record) let `underwriting` and `lending` resolve the PayPal account of a member, for example to disburse a loan. Both values come only from the PayPal login record and are empty for an unknown member; the email may be null when PayPal shared none. They are personal data, so callers must not log them, and `PayoutRecipient` prints a masked `toString`. The `identity.api.events` package is exposed as the named interface `api-events`, so modules such as `underwriting` can listen to `ConsentRevoked` without a module violation.
