package com.paypalseed.engagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.engagement.domain.model.LoanMessages;
import org.junit.jupiter.api.Test;

class LoanMessagesTest {

    @Test
    void aReturnedPayoutSaysTheMoneyDidNotArriveTheMemberOwesNothingAndWeWillTryAgain() {
        String en = LoanMessages.payoutReturned("en", 0);
        assertThat(en).startsWith("Seed:").contains("didn't reach your PayPal account").contains("You owe nothing")
                .contains("no fees").contains("try again once your PayPal account is fixed").doesNotContain("$");
        assertThat(en.length()).as("fits two SMS segments at most").isLessThanOrEqualTo(320);
    }

    @Test
    void whatTheMemberAlreadyPaidIsSaidToBeComingBackFromTheStructuredAmount() {
        String en = LoanMessages.payoutReturned("en", 15_050);
        assertThat(en).contains("$150.50 you paid is coming back").contains("You owe nothing");
        assertThat(en.length()).isLessThanOrEqualTo(320);
        assertThat(LoanMessages.payoutReturned("es", 15_000)).contains("$150").contains("No debes nada").contains("devolvemos");
    }

    @Test
    void aCancellationInTheCoolingOffWindowIsConfirmedWithoutBlame() {
        assertThat(LoanMessages.cancelled("en")).isEqualTo("Seed: Your loan is cancelled. You owe nothing and there are no fees.");
        assertThat(LoanMessages.cancelled("es")).contains("cancelado").contains("No debes nada");
    }

    @Test
    void aCancellationAfterPayingSaysTheRefundIsComingAndOneBeforeNeverPromisesOne() {
        String en = LoanMessages.cancelled("en", 15_050);
        assertThat(en).contains("cancelled").contains("$150.50").contains("coming back to you");
        assertThat(LoanMessages.cancelled("es", 15_000)).contains("$150").contains("devolvemos");
        assertThat(LoanMessages.cancelled("en", 0)).isEqualTo(LoanMessages.cancelled("en")).doesNotContain("coming back");
    }

    @Test
    void spanishForSpanishSpeakersEnglishForAnythingElseAndNoReasonCodeOrPressure() {
        assertThat(LoanMessages.payoutReturned("fr", 0)).isEqualTo(LoanMessages.payoutReturned("en", 0));
        assertThat(LoanMessages.payoutReturned("es", 0)).contains("No debes nada").contains("cuenta de PayPal esté arreglada");
        for (String locale : new String[] {"en", "es"}) {
            for (String text : new String[] {LoanMessages.payoutReturned(locale, 0), LoanMessages.payoutReturned(locale, 500),
                    LoanMessages.cancelled(locale)}) {
                assertThat(text).doesNotContain("RETURNED").doesNotContain("FAILED").doesNotContain("RECEIVER").doesNotContain("error")
                        .doesNotContain("overdue").doesNotContain("must").doesNotContain("immediately");
            }
        }
    }
}
