package com.paypalseed.engagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.engagement.domain.model.SavingsMessages;
import org.junit.jupiter.api.Test;

class SavingsMessagesTest {

    @Test
    void aFailedWithdrawalTellsTheMemberTheMoneyIsSafeAndFree() {
        String en = SavingsMessages.withdrawalFailed("en", 2_500);
        assertThat(en).startsWith("Seed:").contains("$25").contains("back in your goal").contains("no fees").contains("try again");
        assertThat(en.length()).as("fits two SMS segments at most").isLessThanOrEqualTo(320);
    }

    @Test
    void aReturnedWithdrawalSaysTheMoneyIsBackInTheGoal() {
        String en = SavingsMessages.withdrawalReturned("en", 1_250);
        assertThat(en).startsWith("Seed:").contains("$12.50").contains("in your goal again").contains("no fees");
    }

    @Test
    void spanishIsUsedForSpanishSpeakingMembersAndEnglishForAnythingElse() {
        assertThat(SavingsMessages.withdrawalFailed("es", 2_500)).contains("Tu retiro de $25").contains("no hay cargos");
        assertThat(SavingsMessages.withdrawalReturned("es", 2_500)).contains("PayPal devolvió").contains("tu meta");
        assertThat(SavingsMessages.withdrawalFailed("fr", 2_500)).isEqualTo(SavingsMessages.withdrawalFailed("en", 2_500));
    }

    @Test
    void noMessageCarriesAReasonCodeOrAnythingThatLooksLikeBlame() {
        for (String locale : new String[] {"en", "es"}) {
            for (String text : new String[] {SavingsMessages.withdrawalFailed(locale, 100), SavingsMessages.withdrawalReturned(locale, 100)}) {
                assertThat(text).doesNotContain("UNCLAIMED").doesNotContain("RETURNED").doesNotContain("FAILED").doesNotContain("error");
            }
        }
    }
}
