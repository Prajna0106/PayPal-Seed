package com.paypalseed.savings.application.usecases;

import java.util.UUID;

/**
 * A report from PayPal names a goal that is not visible yet. It is thrown out of an event listener on purpose: the event
 * publication stays incomplete and the registry delivers the event again, by which time the goal's own transaction has
 * committed (ADR 0027). It is not a {@code DomainException}: no caller turns it into an HTTP answer.
 */
public class GoalNotVisibleYetException extends RuntimeException {

    private final UUID goalId;

    public GoalNotVisibleYetException(UUID goalId, String what) {
        super(what + " is for goal " + goalId + ", which is not visible yet; the event will be delivered again");
        this.goalId = goalId;
    }

    public UUID goalId() {
        return goalId;
    }
}
