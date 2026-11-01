package com.paypalseed.engines.rules.hardship;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.engines.rules.hardship.GroundingChecker.Allowed;
import com.paypalseed.engines.rules.hardship.GroundingChecker.Audience;
import com.paypalseed.engines.rules.hardship.GroundingChecker.Result;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** TC-AGT-02 to TC-AGT-04: the grounding checker of 03 §11 with Ana's allowed figures. */
class GroundingCheckerTest {

    static final Allowed ANA = new Allowed(
            Set.of(15_000L, 11_200L, 11_300L, 6_900L, 8_100L),
            Set.of(5_200),
            Set.of(LocalDate.of(2027, 3, 20), LocalDate.of(2027, 4, 3), LocalDate.of(2027, 9, 20), LocalDate.of(2027, 10, 20)),
            Set.of(8, 4),
            Set.of(14),
            Set.of(1, 2),
            Set.of("A", "B", "C", "D"));

    @Test
    void thePassingExamplesOfTheDesign() {
        assertPass("Option C makes each payment $112 or $113, and your last payment moves to October 20.");
        assertPass("Your March 20 payment of $150 can move 14 days later, to April 3rd. You can also pay $69 now.");
        assertPass("Your sales look lower than usual. Here are four ways to make March easier. You choose, and no fee applies to any of them.");
        assertPass("Your income over the last 4 weeks is 52% lower than usual, so 8 payments of $112 or $113 may help.");
        assertPass("A one-month pause moves $150 to September 20. That is Option B.");
        assertPass("Option C adds 2 more months; eight instalments remain.");
    }

    @Test
    void anyNumberTheCodeDidNotProduceFails() {
        assertFail("Option C makes each payment about $110.", GroundingChecker.FAIL_MONEY);
        assertFail("Your payment of $150.50 is due.", GroundingChecker.FAIL_MONEY);
        assertFail("Your income is 60% lower than usual.", GroundingChecker.FAIL_PERCENT);
        assertFail("We can move your payment to March 21.", GroundingChecker.FAIL_DATE);
        assertFail("We can move your payment 15 days later.", GroundingChecker.FAIL_DURATION);
        assertFail("You would have 9 payments left after this change.", GroundingChecker.FAIL_COUNT);
        assertFail("You could also choose Option E for your loan.", GroundingChecker.FAIL_OPTION);
        assertFail("Your score went from 3 to 7 this week, sadly.", GroundingChecker.FAIL_NUMBER);
        assertFail("Your next payment is on 2027-03-21 as planned.", GroundingChecker.FAIL_DATE);
    }

    @Test
    void percentAllowsHalfAPointOfRounding() {
        assertPass("Sales are down 52.4% from your usual weeks.");
        assertFail("Sales are down 52.6% from your usual weeks.", GroundingChecker.FAIL_PERCENT);
    }

    @Test
    void forbiddenContentFails() {
        assertFail("Your payment is waived for this month.", GroundingChecker.FAIL_FORBIDDEN);
        assertFail("Act now: this is your last chance to choose.", GroundingChecker.FAIL_FORBIDDEN);
        assertFail("Visit paypal-seed.com to choose an option.", GroundingChecker.FAIL_FORBIDDEN);
        assertFail("Write to help@seed.example to choose an option.", GroundingChecker.FAIL_FORBIDDEN);
        assertFail("Call us on 415 555 0199 to choose an option.", GroundingChecker.FAIL_FORBIDDEN);
        assertFail("You can pay 150 euros instead of the usual amount.", GroundingChecker.FAIL_FORBIDDEN);
        assertFail("This will have no effect on your record, we promise.", GroundingChecker.FAIL_FORBIDDEN);
    }

    @Test
    void spanishTextIsCheckedTheSameWay() {
        Result ok = GroundingChecker.check("Tu pago de $150 del 20 de marzo puede moverse 14 días, hasta el 3 de abril. "
                + "También puedes elegir la opción C con pagos de 112 dólares.", ANA, "es", Audience.MEMBER, Ana.AS_OF);
        assertThat(ok.passed()).as(ok.toString()).isTrue();

        Result bad = GroundingChecker.check("Tu pago del 21 de marzo puede moverse para que tengas más tiempo.", ANA, "es",
                Audience.MEMBER, Ana.AS_OF);
        assertThat(bad.failureKind()).isEqualTo(GroundingChecker.FAIL_DATE);
    }

    @Test
    void languageAndLengthMustMatchTheAudience() {
        Result wrongLanguage = GroundingChecker.check("Your payment of $150 is due and you can choose an option that helps.",
                ANA, "es", Audience.MEMBER, Ana.AS_OF);
        assertThat(wrongLanguage.failureKind()).isEqualTo(GroundingChecker.FAIL_LANGUAGE);

        String long481 = "a".repeat(481);
        assertThat(GroundingChecker.check(long481, ANA, "en", Audience.MEMBER, Ana.AS_OF).failureKind())
                .isEqualTo(GroundingChecker.FAIL_LENGTH);
        assertThat(GroundingChecker.check(long481, ANA, "en", Audience.OFFICER, Ana.AS_OF).passed()).isTrue();
        assertThat(GroundingChecker.check(" ", ANA, "en", Audience.MEMBER, Ana.AS_OF).failureKind())
                .isEqualTo(GroundingChecker.FAIL_EMPTY);
    }

    @Test
    void optionPhrasesMustBeOffered() {
        Allowed onlyA = new Allowed(ANA.moneyCents(), ANA.percentBps(), ANA.dates(), ANA.counts(), ANA.durationsDays(),
                ANA.durationsMonths(), Set.of("A"));
        Result r = GroundingChecker.check("You could pause this payment for a while.", onlyA, "en", Audience.MEMBER, Ana.AS_OF);
        assertThat(r.failureKind()).isEqualTo(GroundingChecker.FAIL_OPTION);
    }

    @Test
    void spanishOptionWordIsNotALabel() {
        assertThat(GroundingChecker.check("Tienes una opción y nadie te obliga: la opción a elegir es tuya.", ANA, "es",
                Audience.MEMBER, Ana.AS_OF).passed()).isTrue();
        assertThat(GroundingChecker.check("Puedes elegir la opción E.", ANA, "es", Audience.MEMBER, Ana.AS_OF).failureKind())
                .isEqualTo(GroundingChecker.FAIL_OPTION);
    }

    @Test
    void listOrdinalsAndYearsInsideDatesAreIgnored() {
        assertPass("1. Move $150 to April 3rd, 2027.\n2. Pause it until September 20.");
    }

    private static void assertPass(String text) {
        Result r = GroundingChecker.check(text, ANA, "en", Audience.MEMBER, Ana.AS_OF);
        assertThat(r.passed()).as(text + " -> " + r).isTrue();
    }

    private static void assertFail(String text, String kind) {
        Result r = GroundingChecker.check(text, ANA, "en", Audience.MEMBER, Ana.AS_OF);
        assertThat(r.passed()).as(text).isFalse();
        assertThat(r.failureKind()).as(text + " -> " + r).isEqualTo(kind);
    }
}
