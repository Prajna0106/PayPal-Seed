package com.paypalseed.platform.security;

/**
 * Why an actor token was refused. Logged and counted by name only; the HTTP response never says
 * which check failed, because that would tell an attacker what to fix.
 */
public enum ActorTokenFailure {
    NOT_CONFIGURED,
    MALFORMED,
    ALGORITHM,
    HEADER,
    UNKNOWN_KEY,
    SIGNATURE,
    ISSUER,
    AUDIENCE,
    SUBJECT,
    ACTOR_TYPE,
    ROLES,
    MISSING_CLAIM,
    EXPIRED,
    NOT_YET_VALID,
    LIFETIME
}
