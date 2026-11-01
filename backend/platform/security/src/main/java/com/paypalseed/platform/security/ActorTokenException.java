package com.paypalseed.platform.security;

/** A refused actor token. The message is the failure name only: it never carries token content. */
public final class ActorTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ActorTokenFailure failure;

    public ActorTokenException(ActorTokenFailure failure) {
        super(failure.name(), null, false, false);
        this.failure = failure;
    }

    public ActorTokenFailure failure() {
        return failure;
    }
}
