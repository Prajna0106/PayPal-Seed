package com.paypalseed.savings.application.usecases;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Settings of the PayPal-backed savings flows.
 *
 * @param returnUrl      where PayPal sends the member after approving the subscription
 * @param cancelUrl      where PayPal sends the member who backs out
 * @param approvalWindow how long a goal may wait for the member to approve before it expires
 */
public record SavingsFlowSettings(String returnUrl, String cancelUrl, Duration approvalWindow) {

    public SavingsFlowSettings {
        Objects.requireNonNull(returnUrl, "returnUrl");
        Objects.requireNonNull(cancelUrl, "cancelUrl");
        Objects.requireNonNull(approvalWindow, "approvalWindow");
        if (approvalWindow.isNegative() || approvalWindow.isZero()) {
            throw new IllegalArgumentException("approvalWindow must be positive");
        }
    }

    /** The return URL tells the page which goal the member just approved. */
    public String returnUrlFor(UUID goalId) {
        return withGoal(returnUrl, goalId);
    }

    public String cancelUrlFor(UUID goalId) {
        return withGoal(cancelUrl, goalId);
    }

    private static String withGoal(String url, UUID goalId) {
        return url + (url.contains("?") ? "&" : "?") + "goalId=" + goalId;
    }
}
