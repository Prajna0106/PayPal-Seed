package com.paypalseed;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The member Home page says whether a payout can be sent before the member tries (ADR 0027): {@code payoutRecipientReady}
 * is true when identity holds a payout recipient and false for a member who never signed in with PayPal, who would get
 * {@code 409 payout-recipient-missing} on a withdrawal.
 */
class HomePayoutRecipientIT extends MoneyFlowSupport {

    private ResultActions home(UUID member) throws Exception {
        return mvc.perform(get("/api/v1/members/me/home").header("X-Seed-Member-Id", member.toString()));
    }

    @Test
    void aMemberWhoSignedInWithPayPalIsReady() throws Exception {
        UUID member = newMember();

        home(member).andExpect(status().isOk()).andExpect(jsonPath("$.payoutRecipientReady").value(true))
            .andExpect(jsonPath("$.profile").exists());
    }

    @Test
    void aMemberWithoutAPayPalLoginIsNotReadyAndTheWithdrawalAgreesWithTheWarning() throws Exception {
        UUID member = UUID.randomUUID(); // never signed up, so identity knows no account to pay into

        home(member).andExpect(status().isOk()).andExpect(jsonPath("$.payoutRecipientReady").value(false))
            .andExpect(jsonPath("$.profile").doesNotExist());

        String goalId = legacyGoal(member, 25).path("id").asText();
        legacyTopUp(member, goalId, 100);
        mvc.perform(asMember(post("/api/v1/savings/goals/{id}/withdraw", goalId), member).header("Idempotency-Key", "home-w")
                .content("{\"amount\":30}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value(Matchers.endsWith("payout-recipient-missing")));
    }

    @Test
    void theFlagIsTheCallersOwnAndNeverAnotherMembers() throws Exception {
        UUID ready = newMember();
        UUID notReady = UUID.randomUUID();

        home(notReady).andExpect(jsonPath("$.payoutRecipientReady").value(false));
        home(ready).andExpect(jsonPath("$.payoutRecipientReady").value(true));
    }

    @Test
    void theFlagCarriesNoPersonalDataAndTheResponseIsStillNotCached() throws Exception {
        UUID member = newMember();

        home(member).andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control",
                Matchers.containsString("no-store")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                Matchers.not(Matchers.containsString("PAYER-"))));
    }
}
