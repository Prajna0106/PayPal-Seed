# ADR 0023: Staff Passwords and Checking Access on Every Call

## Status
Accepted

## Context
ADR 0016 makes the web servers sign short actor tokens from their session cookie, which lasts eight hours. The backend verified the signature and nothing else, so suspending someone or removing a role had no effect until the cookie ran out. The desk also had no real login, only demo personas. Both web servers held the same signing key and the same service token, so a break-in on the public member server would have reached everything the desk can do.

## Decision
**Staff log in with an email and a password.** The email is the user ID. Passwords are stored only as Argon2id hashes (19 MiB, two passes, one lane). The desk's server calls `POST /internal/auth/staff/password/login` and starts its session from the answer.

**The desk has its own credentials.** Staff tokens are signed with `SEED_STAFF_ACTOR_TOKEN_SECRETS`, which only the desk and the api hold; member tokens keep `SEED_ACTOR_TOKEN_SECRETS`. The verifier picks the key set from the token's actor type and then checks the signature with it, so neither app can sign the other's kind of token. The staff login and account routes (`/internal/auth/staff/**` and `/internal/staff/**`) take `SEED_STAFF_SERVICE_TOKEN`, a role (`STAFF_ACCOUNTS`) that opens nothing else; the member app's service token gets `403` there. Outside dev, test and demo the api refuses to start without the staff keys, or if they reuse a member key, and the two service tokens must differ.

**Admins manage staff.** There is no public sign-up. On the desk's Staff page an admin creates colleagues, changes roles, suspends and restores them, links their member account and resets a lost password. Nobody can take away their own admin role or suspend themselves, and the last active admin can't be removed. Someone whose password an admin chose must change it before anything else: until then the backend gives them no roles. `scripts/create-staff.sh` creates the first admin; for an existing email it only sets a new password, and adds a member link if one is given. It never changes roles or clears a link.

**Every change to an account is audited.** Creating an account, a password reset or change, an access change and a save of the directory row each append to the governance audit chain. Entries name roles, active and whether a member is linked, never names, emails or member ids.

**Every failed login looks the same.** A wrong password, an unknown email, a suspended person and a lockout all return `401 staff-login-refused`, and a hash always runs. Failures count in a 15-minute window, per client address and per account. Five from one address lock that address for 15 minutes, so a guesser can't keep a colleague locked out from elsewhere; fifty across all addresses lock the account, which stops guessing spread over many addresses. Each count is one SQL statement, so parallel guesses are each counted. The desk also limits attempts per address before calling the backend, reading the client address `SEED_TRUSTED_PROXY_HOPS` places from the end of `X-Forwarded-For`.

**The directory has the last word on staff.** After a token or dev header is accepted, `ActorAuthentication` asks `StaffAccessLookup`, which `identity` implements. Staff it doesn't know are refused unless dev headers are on (the demo personas are seeded in dev and demo). A suspended person gets `401`. Roles are what the token names and the directory still grants, so a removed role stops counting at once. This is one primary key read per staff request.

**Sessions can be ended on the server.** A staff token carries `auth_time`, when its desk session began. Logging out, a password change or an admin's reset sets `sessions_valid_after`, and any signed session that began earlier gets `401`, including a copied cookie. The desk starts a fresh session after a password change.

**Staff can't decide their own case.** `identity.staff.linked_member_id` (one staff login per member account) reaches `Actor.linkedMemberId`, so loans, fair checks, hardship cases and safety fund covers, including creating a cover request, refuse them with `sod-violation`.

**Demo sign-in has a switch.** Ana's story, the member id box and the desk personas let anyone in without a password. They work only when `SEED_DEMO_LOGIN` is true, which is the default outside production and never the default in production. A demo session stops working when it is off.

**Members are keyed by PayPal's `payer_id`.** ADR 0012 says `sub`, but userinfo returns the two as different values, and payouts send `paypal_payer_id` to PayPal as a PayPal id. The member app therefore sends `payer_id`, and refuses a login without one.

## Consequences
- Suspending someone, removing a role, logging out and changing a password all take effect on the next request.
- Logging out on one device ends that person's sessions on every device.
- Production needs two more values: `SEED_STAFF_SERVICE_TOKEN` (Render generates it) and `SEED_STAFF_ACTOR_TOKEN_SECRETS` (set once by hand).
- The desk's own rate limit lives in its memory; the backend's lockout is the one that persists.
- Members have no suspended state, so nothing checks one.

## Alternatives
- **Passkeys.** `plan.md` names them and they resist phishing better, but they were judged too much for this stage: a device per person, a domain tied to each deployment, and recovery when a device is lost. Passwords can be joined by passkeys later.
- **Backend session table instead of ADR 0016 tokens.** It gives per-device logout, but it reverses an accepted decision that is already implemented; `auth_time` gives server-side logout with one column.
- **Allow the account route only while there is no admin.** Simpler, but it would leave no way to recover a lost admin password without a reset screen; a separate token keeps the script useful and still out of the member app's reach.
- **Check access on the desk's server.** A compromised or stale web server would then be the only guard, so the check lives where the data is.
