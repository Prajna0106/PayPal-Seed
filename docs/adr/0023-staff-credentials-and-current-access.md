# ADR 0023: Staff Passwords and Checking Access on Every Call

## Status
Accepted

## Context
ADR 0016 makes the web servers sign short actor tokens from their session cookie, which lasts eight hours. The backend verified the signature and nothing else, so suspending someone or removing a role had no effect until the cookie ran out. The desk also had no real login, only demo personas.

## Decision
**Staff log in with an email and a password.** The email is the user ID. Passwords are stored only as Argon2id hashes (19 MiB, two passes, one lane). The desk's server calls `POST /internal/auth/staff/password/login` and starts its session from the answer.

**Admins create staff.** There is no public sign-up. `POST /api/v1/desk/staff` needs the `ADMIN` role, because anyone else could create themselves a stronger account. It refuses an email that is already taken, so it can't take over a colleague's account. The admin sets a starting password and the person changes it on the Your login page. `scripts/create-staff.sh` exists only for the first admin, who has nobody to create them.

**Every failed login looks the same.** A wrong password, an unknown email, a suspended person and a locked account all return `401 staff-login-refused`, and a hash always runs. Five wrong passwords lock the account for 15 minutes. The desk also limits attempts per address.

**The directory has the last word on staff.** After a token or dev header is accepted, `ActorAuthentication` asks `StaffAccessLookup`, which `identity` implements. A suspended person gets `401`. Roles are what the token names and the directory still grants, so a removed role stops counting at once. This is one primary key read per staff request. People the directory doesn't know keep what the token says, so a signed token still works without a row.

**Staff can link their member account.** `identity.staff.linked_member_id` reaches `Actor.linkedMemberId`, so governance and the loan routes refuse them on their own case with `sod-violation`.

**Demo sign-in has a switch.** Ana's story, the member id box and the desk personas let anyone in without a password. They work only when `SEED_DEMO_LOGIN` is true, which is the default outside production and never the default in production. A demo session stops working when it is off.

**Members are keyed by PayPal's `payer_id`.** ADR 0012 says `sub`, but userinfo returns the two as different values, and payouts send `paypal_payer_id` to PayPal as a PayPal id. The member app therefore sends `payer_id`, and refuses a login without one.

## Consequences
- Suspending someone or removing a role works on their next request.
- Logging out clears the cookie but doesn't revoke it, and a new password doesn't end other sessions: a stolen cookie keeps working until it expires. Ending sessions on the server would need a claim for when the person logged in, or a session table.
- Members have no suspended state, so nothing checks one.
- The admin knows a starting password until the person changes it, and nothing forces that change yet.
- The desk has no screen to suspend someone or change roles yet; `PUT /internal/staff/{staffId}` does it.
- Creating an account writes a log line, not a governance audit entry.

## Alternatives
- **Passkeys.** `plan.md` names them and they resist phishing better, but they were judged too much for this stage: a device per person, a domain tied to each deployment, and recovery when a device is lost. Passwords can be joined by passkeys later.
- **Backend session table instead of ADR 0016 tokens.** It gives instant logout too, but it reverses an accepted decision that is already implemented.
- **Check access on the desk's server.** A compromised or stale web server would then be the only guard, so the check lives where the data is.
