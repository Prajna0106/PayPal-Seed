package com.paypalseed.platform.security;

import java.util.Optional;

/** Turns a signed-in user's bearer token into an {@link Actor}. A port, so platform never depends on a module. */
public interface ActorTokenResolver {

    /** The actor, or empty when the token is unknown, expired or revoked. */
    Optional<Actor> resolve(String bearerToken);
}
