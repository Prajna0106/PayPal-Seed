package com.paypalseed.platform.security;

import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who is calling. Controllers receive it as a method argument and state what they require, so every
 * route declares its role in code a reviewer can see.
 */
public record Actor(Type type, UUID id, Set<Role> roles, UUID linkedMemberId) {

    public enum Type {
        MEMBER,
        STAFF,
        SERVICE,
        AGENT
    }

    public enum Role {
        MEMBER,
        OFFICER,
        CHECKER,
        ADMIN,
        SERVICE,
        /** Agents get read-only scopes only (ADR 0003). */
        AGENT_READ
    }

    public Actor {
        Objects.requireNonNull(type, "type");
        roles = roles.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(roles));
    }

    /** Someone with no member account linked to this identity. */
    public Actor(Type type, UUID id, Set<Role> roles) {
        this(type, id, roles, null);
    }

    public static Actor member(UUID memberId) {
        return new Actor(Type.MEMBER, memberId, Set.of(Role.MEMBER));
    }

    public static Actor service(String name) {
        return new Actor(Type.SERVICE, UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Set.of(Role.SERVICE));
    }

    /** Whether this person is {@code memberId}, either directly or as staff with that member account. */
    public boolean isMember(UUID memberId) {
        return memberId != null && (memberId.equals(id) || memberId.equals(linkedMemberId));
    }

    /** The member id, or 403 for anyone who is not a member. */
    public UUID requireMember() {
        if (type != Type.MEMBER) {
            throw new DomainException(CommonErrors.FORBIDDEN, "This route is for members");
        }
        return id;
    }

    /** Passes if the actor holds any of {@code anyOf}; otherwise 403. */
    public Actor requireAnyRole(Role... anyOf) {
        for (Role r : anyOf) {
            if (roles.contains(r)) {
                return this;
            }
        }
        throw new DomainException(CommonErrors.FORBIDDEN, "Requires one of " + Set.of(anyOf));
    }
}
