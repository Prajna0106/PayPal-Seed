package com.paypalseed.savings.api;

import com.paypalseed.platform.kernel.Money;

/**
 * PUBLIC API facade for the savings bounded context.
 * Only this package may be imported by other modules (such as payments).
 */
public interface SavingsFacade {

    /**
     * Records a deposit initiated by a PayPal recurring subscription sale.
     *
     * @param subscriptionId  PayPal billing agreement / subscription ID (e.g., I-BW452GLLEP1G)
     * @param amount          Amount captured
     * @param payPalCaptureId Unique transaction/capture ID
     */
    void recordDepositBySubscription(String subscriptionId, Money amount, String payPalCaptureId);

    /**
     * Pauses the goal when a member cancels or suspends their PayPal subscription.
     *
     * @param subscriptionId PayPal subscription ID
     */
    void pauseGoalBySubscription(String subscriptionId);
}
