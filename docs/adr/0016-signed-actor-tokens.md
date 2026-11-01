# ADR 0016: Signed Actor Tokens Replace Trusted Identity Headers

## Status
Accepted

## Context
Controllers receive an `Actor` built by `ActorAuthentication`. Until now it came from `X-Seed-Member-Id`, `X-Seed-Staff-Id` and `X-Seed-Staff-Roles`, which let any caller choose any identity. That is acceptable only in dev, test and demo (`seed.security.dev-header-auth=true`). In production the two Next.js servers authenticate the person (Log in with PayPal for members, staff SSO for officers) and must prove to the backend who the actor is. The backend also has a second credential, the internal service token (`SEED_INTERNAL_TOKEN`), for `/internal/**` jobs. Agents keep their own run tokens on `/agent/v1/**` (ADR 0003, ADR 0010) and are not touched here.

## Decision
The web servers send `Authorization: Bearer <actor token>` on every `/api/**` call. The token is a compact JWT signed with HS256 using a secret shared by the web servers and the backend. The backend verifies it and builds the same `Actor` the controllers already use. No controller and no public shape of `Actor` changes.

### Claims

| Claim | Required | Value and rule |
| --- | --- | --- |
| header `alg` | yes | exactly `HS256`; anything else is refused before a key is touched |
| header `typ` | yes | exactly `JWT` |
| header `kid` | yes when more than one key is configured | must match a configured key id; an unknown `kid` is refused |
| header, other parameters | no | any parameter besides `alg`, `typ`, `kid` (`jku`, `jwk`, `x5u`, `crit`, `zip`, `cty`...) refuses the token |
| `iss` | yes | `seed-web` |
| `aud` | yes | `seed-api`, as the only audience |
| `sub` | yes | member or staff UUID in canonical text form |
| `act` | yes | `member` or `staff` |
| `roles` | staff only | array of `Actor.Role` names, at most 8. Allowed: `OFFICER`, `CHECKER`, `ADMIN`. `MEMBER`, `SERVICE`, `AGENT_READ` and any unknown name refuse the whole token. Missing or empty gives a valid staff actor with no powers (desk routes answer 403). Ignored on a member token, which always holds `MEMBER` only. |
| `iat` | yes | not more than 30 s in the future |
| `exp` | yes | `exp - iat` must be positive and at most 300 s; expired when now is more than 30 s past `exp` |
| `nbf` | no | if present, not more than 30 s in the future |
| `jti` | yes | non-blank, at most 128 characters (random UUID) |

The clock skew is 30 s and cannot be configured above that. The web server should issue tokens that live 60 s or less, one per outgoing request or per page render.

Claims are examined only after the signature verified. The signature is checked with Nimbus JOSE (`MACVerifier`, constant-time comparison). Nimbus is added through `gradle/libs.versions.toml` (`nimbus-jose-jwt`); it was not on the classpath before.

### Secrets and rotation
`seed.security.actor-token.secrets` is a list of `kid:secret` entries, comma separated when given as one string (env `SEED_ACTOR_TOKEN_SECRETS`; the Spring-relaxed name `SEED_SECURITY_ACTORTOKEN_SECRETS` binds too). The secret is everything after the first colon, at least 32 characters, so avoid commas (use `openssl rand -base64 48`). `kid` is 1 to 32 characters from `A-Za-z0-9._-`, unique. The first entry is the signing key; every entry verifies. A token without `kid` is accepted only while exactly one key is configured.

Outside the `dev`, `test` and `demo` profiles (a run with no profile counts as production) the application fails to start if no secret is configured or if `seed.security.dev-header-auth` is true. Start-up errors name the setting or the `kid`, never the secret.

Rotation checklist:
1. Generate the new secret. Add it as the **second** entry on every backend instance (`old:...,new:...`) and deploy the backend. Both keys verify now; web servers still sign with `old`.
2. Put the new key **first** in the web servers' list and deploy them. They now sign with `new`.
3. Wait longer than the longest token life plus skew (6 minutes).
4. Remove the old entry from the backend and the web servers and deploy. Tokens signed with `old` now fail with `UNKNOWN_KEY`.
5. If a secret leaked, skip the wait: do step 4 at once. Every outstanding token is invalid within one deploy.

### Two credentials, two path families
| Credential | `/api/**` | `/internal/**` | elsewhere |
| --- | --- | --- | --- |
| actor token | the named member or staff actor | refused (401) | refused (401) |
| internal service token | refused (401) | `SERVICE` actor | refused (401) |
| dev headers (dev, test, demo only) | as before | as before | as before |

A bearer credential that is wrong for its path is final: dev headers do not rescue it. If both a valid token and dev headers are present in dev, the token wins. The path is normalised first (context path, `;params`, repeated slashes and dot segments removed) and must then start with the expected prefix, so unusual spellings fail closed. Roles still back this up: actor tokens never carry `SERVICE`, so `/internal/**` controllers would answer 403 even if the path check were bypassed. A member token on a staff-only route authenticates as a member and the controller answers 403.

### Responses
Missing, malformed, expired, not-yet-valid or badly signed tokens give `401` `application/problem+json` (`https://problems.paypal-seed.dev/unauthenticated`) with `WWW-Authenticate: Bearer` and the same body whatever the reason. A valid actor without the role gets `403` (`forbidden`) from the controller, as before.

### Observability
Each refusal logs at WARN `Actor token rejected: <FAILURE>` where the failure is one of `NOT_CONFIGURED, MALFORMED, ALGORITHM, HEADER, UNKNOWN_KEY, SIGNATURE, ISSUER, AUDIENCE, SUBJECT, ACTOR_TYPE, ROLES, MISSING_CLAIM, EXPIRED, NOT_YET_VALID, LIFETIME`. The token and its claims are never logged. No Micrometer metric is emitted: the codebase does not use the metrics API yet. When it does, a counter tagged by failure belongs in `ActorAuthentication.bearer`.

### Issuer helper and dev tool
`ActorTokenIssuer` (`platform/security`) mints tokens the same way the web server does and is used by the tests. For manual calls:

```bash
export SEED_ACTOR_TOKEN_SECRETS="k1:$(openssl rand -base64 48)"
./gradlew :platform:security:mintActorToken -q -Ptype=member -Pid=<member-uuid>
./gradlew :platform:security:mintActorToken -q -Ptype=staff  -Pid=<staff-uuid> -Proles=OFFICER
```
Run the backend with the same variable and without `dev-header-auth` to try the production path locally.

### Frontend example (Node, `jose`)
```js
import { SignJWT } from "jose";
import { randomUUID } from "node:crypto";

const [kid, ...rest] = process.env.SEED_ACTOR_TOKEN_SECRETS.split(",")[0].split(":");
const key = new TextEncoder().encode(rest.join(":"));

export function actorToken({ id, type, roles = [] }) { // type: "member" | "staff"
  return new SignJWT(type === "staff" ? { act: type, roles } : { act: type })
    .setProtectedHeader({ alg: "HS256", typ: "JWT", kid })
    .setIssuer("seed-web")
    .setAudience("seed-api")
    .setSubject(id)
    .setIssuedAt()
    .setExpirationTime("60s")
    .setJti(randomUUID())
    .sign(key);
}
// fetch(`${API}/api/v1/loans`, { headers: { Authorization: `Bearer ${await actorToken(...)}` } })
```
The secret stays in server-only code. The staff `roles` come from the SSO group mapping, never from a request.

## Consequences
- A caller can no longer pick an identity in production; the worst a stolen token allows is the actor it names for at most five minutes.
- The backend and both web servers share one symmetric secret per key id. A compromise of a web server is a compromise of every actor, which is the same trust the web servers already held. Asymmetric signing (EdDSA with a JWKS) is the upgrade path if more issuers appear; the algorithm is fixed in code, so that change is a deliberate release.
- There is no `jti` replay store. Replay within the token life is possible for someone who captured a token; every write also needs its `Idempotency-Key`, and tokens live about a minute.
- Rotation needs two deploys on each side, written down above.
- Local development is unchanged: dev, test and demo still accept headers, and existing integration tests keep using them.
- Needed from the application owner: `application.yml` should declare `seed.security.actor-token.secrets: ${SEED_ACTOR_TOKEN_SECRETS:}` and `infra/render.yaml` should set the variable on the backend and both web services. The code already reads the environment variable directly, so production works without that edit; the entry only makes the key discoverable.
