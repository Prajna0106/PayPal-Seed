package com.paypalseed.savings.api;

import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.dto.DepositFact;
import com.paypalseed.savings.api.dto.MemberGoalView;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import java.util.List;

/**
 * PUBLIC API facade for the savings bounded context.
 * Only this package may be imported by other modules (such as payments and lending).
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

    /**
     * Returns the savings consistency and balance summary for a member, used by lending eligibility (Feature 2).
     */
    default MemberSavingsSummary getMemberSavingsSummary(String memberId) {
        return new MemberSavingsSummary(memberId, 0, 0, 0, 0L, 0L, false);
    }

    /** The member's goals, newest first. Only that member's; an unknown member has none. */
    default List<MemberGoalView> goalsOfMember(String memberId) {
        return List.of();
    }

    /**
     * Members who have had a deposit credited, after {@code afterMemberId} (null starts at the beginning), in id order,
     * at most {@code limit}. The last id of a page is the {@code afterMemberId} of the next; an empty page is the end.
     */
    default List<String> membersWithDeposits(String afterMemberId, int limit) {
        return List.of();
    }

    /**
     * Every deposit credited to any goal of the member, oldest first, with the instant PayPal's money was credited. A read
     * model that has to be rebuilt from the money itself (desk-query, for the saving months) uses this with
     * {@link #membersWithDeposits}.
     */
    default List<DepositFact> depositsOfMember(String memberId) {
        return List.of();
    }
}
