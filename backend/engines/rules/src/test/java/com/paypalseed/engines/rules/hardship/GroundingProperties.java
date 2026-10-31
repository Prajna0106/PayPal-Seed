package com.paypalseed.engines.rules.hardship;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.engines.rules.hardship.GroundingChecker.Allowed;
import com.paypalseed.engines.rules.hardship.GroundingChecker.Audience;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/** PROP-H-08: every allowed value, however it is written, passes; any value off by one unit fails. */
class GroundingProperties {

    private static final LocalDate SCAN = LocalDate.of(2027, 3, 10);

    @Property(tries = 1000)
    void allowedMoneyPassesAndAnyOtherAmountFails(@ForAll @LongRange(min = 1, max = 500_000) long dollars,
                                                  @ForAll @IntRange(max = 99) int cents) {
        long amount = dollars * 100 + cents;
        String text = "Your payment would be " + render(amount) + " each month from now on.";

        assertThat(check(text, money(amount)).passed()).as(text).isTrue();
        assertThat(check(text, money(amount + 100)).passed()).as(text).isFalse();
        assertThat(check(text, money(amount - 1)).passed()).as(text).isFalse();
    }

    @Property(tries = 1000)
    void allowedDatesPassWithinAYearOfTheScan(@ForAll @IntRange(min = -330, max = 330) int offset) {
        LocalDate date = SCAN.plusDays(offset);
        String text = "Your next payment would be due on " + date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " " + date.getDayOfMonth() + " instead.";

        assertThat(check(text, dates(date)).passed()).as(text).isTrue();
        assertThat(check(text, dates(date.plusDays(1))).passed()).as(text).isFalse();
    }

    private static GroundingChecker.Result check(String text, Allowed allowed) {
        return GroundingChecker.check(text, allowed, "en", Audience.MEMBER, SCAN);
    }

    private static String render(long cents) {
        String whole = String.format(Locale.ENGLISH, "%,d", cents / 100);
        return "$" + whole + (cents % 100 == 0 ? "" : String.format(Locale.ENGLISH, ".%02d", cents % 100));
    }

    private static Allowed money(long cents) {
        return new Allowed(Set.of(cents), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    private static Allowed dates(LocalDate date) {
        return new Allowed(Set.of(), Set.of(), Set.of(date), Set.of(), Set.of(), Set.of(), Set.of());
    }
}
