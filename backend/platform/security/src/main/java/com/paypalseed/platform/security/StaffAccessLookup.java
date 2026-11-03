package com.paypalseed.platform.security;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the staff directory says about someone right now. A signed token proves who sent a request and what the desk
 * believed when it signed; this is what stops a suspended person from carrying on until their desk session runs out.
 */
public interface StaffAccessLookup {

    /** {@code linkedMemberId} is their own member account, if they have one. */
    record StaffAccess(boolean active, Set<Actor.Role> roles, UUID linkedMemberId) {
    }

    /** Empty when the directory has no such person. */
    Optional<StaffAccess> access(UUID staffId);
}
