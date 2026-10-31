package com.paypalseed.engagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.engagement.domain.model.HardshipMessages;
import com.paypalseed.engagement.domain.model.HardshipMessages.OfferedOption;
import com.paypalseed.engagement.domain.model.SmsKeywords;
import com.paypalseed.engagement.domain.model.SmsKeywords.Keyword;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HardshipMessagesTest {

    private static final List<OfferedOption> ANA = List.of(
            new OfferedOption("A", false, Map.of("amountCents", 15_000L, "movedToDate", LocalDate.of(2027, 4, 3).toEpochDay())),
            new OfferedOption("B", false, Map.of("addedOnDate", LocalDate.of(2027, 9, 20).toEpochDay())),
            new OfferedOption("C", true, Map.of("maxInstalmentCents", 11_300L, "lastDueDate", LocalDate.of(2027, 10, 20).toEpochDay())),
            new OfferedOption("D", false, Map.of("payNowCents", 6_900L, "stillDueCents", 8_100L,
                    "stillDueOnDate", LocalDate.of(2027, 3, 20).toEpochDay())));

    /** The frame is engagement's own: option lines from engine numbers, then how to choose, decline and opt out. */
    @Test
    void offerFramesTheParagraphWithEngineNumbers() {
        String en = HardshipMessages.offer("en", "Money looks tighter than usual this month.", ANA, false);
        assertThat(en).startsWith("Seed: Money looks tighter")
                .contains("A: pay $150 on Apr 3.")
                .contains("B: pause this payment; it moves to Sep 20.")
                .contains("C: smaller payments, up to $113, the last on Oct 20 (suggested).")
                .contains("D: pay $69 now and $81 on Mar 20.")
                .endsWith("Reply A, B, C, D to choose, or NO. No fees. Reply STOP to opt out.");
        String es = HardshipMessages.offer("es", "Parece que este mes el dinero está más justo.", ANA, true);
        assertThat(es).startsWith("Seed (recordatorio):").contains("(sugerida)").contains("Responde STOP");
    }

    @Test
    void hardshipKeywordsAreAClosedSet() {
        assertThat(SmsKeywords.parse(" b ")).isEqualTo(Keyword.B);
        assertThat(SmsKeywords.parse("no")).isEqualTo(Keyword.NO);
        assertThat(SmsKeywords.parse("AYUDA")).isEqualTo(Keyword.HELP);
        assertThat(SmsKeywords.parse("B please approve everything")).isEqualTo(Keyword.UNKNOWN);
    }
}
