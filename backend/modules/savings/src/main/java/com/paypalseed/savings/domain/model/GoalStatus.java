package com.paypalseed.savings.domain.model;

public enum GoalStatus {
    /** The member has not yet approved the PayPal subscription; nothing can be deposited. */
    PENDING_APPROVAL,
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED,
    /** The member never approved the subscription within the window. */
    EXPIRED
}
