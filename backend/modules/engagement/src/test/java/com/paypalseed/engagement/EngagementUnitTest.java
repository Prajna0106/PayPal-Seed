package com.paypalseed.engagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.engagement.domain.model.QuietHours;
import com.paypalseed.engagement.domain.model.ReminderTemplates;
import com.paypalseed.engagement.domain.model.ReminderTemplates.Type;
import com.paypalseed.engagement.domain.model.SmsKeywords;
import com.paypalseed.engagement.infrastructure.clients.ZapierSmsClient;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** TC-REM-03, TC-REM-05, TC-API-08 at unit level. */
class EngagementUnitTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    void tcRem03_quietHoursDeferToEightInTheMorningLocalTime() {
        Instant ninePm = LocalDate.of(2026, 12, 6).atTime(21, 0).atZone(NEW_YORK).toInstant();
        Instant sixAm = LocalDate.of(2026, 12, 7).atTime(6, 0).atZone(NEW_YORK).toInstant();
        Instant noon = LocalDate.of(2026, 12, 7).atTime(12, 0).atZone(NEW_YORK).toInstant();
        Instant eightAm = LocalDate.of(2026, 12, 7).atTime(8, 0).atZone(NEW_YORK).toInstant();

        assertThat(QuietHours.nextAllowed(ninePm, NEW_YORK)).isEqualTo(eightAm);
        assertThat(QuietHours.nextAllowed(sixAm, NEW_YORK)).isEqualTo(eightAm);
        assertThat(QuietHours.nextAllowed(noon, NEW_YORK)).isEqualTo(noon);
    }

    @Test
    void tcRem05_messagesInEnglishAndSpanishNeverMentionFees() {
        String en = ReminderTemplates.render(Type.DUE_SOON, "en", 15_000, LocalDate.of(2026, 12, 11));
        String es = ReminderTemplates.render(Type.DUE_SOON, "es", 15_000, LocalDate.of(2026, 12, 11));
        assertThat(en).isEqualTo("Seed: Your $150 payment is due Friday, December 11. If this month is tight, reply HELP.");
        assertThat(es).contains("$150").contains("viernes 11 de diciembre").contains("AYUDA");
        assertThat(ReminderTemplates.render(Type.PAYMENT_FAILED, "en", 15_000, null)).contains("No fees");
    }

    @Test
    void smsKeywordsAreAClosedSet() {
        assertThat(SmsKeywords.parse(" stop ")).isEqualTo(SmsKeywords.Keyword.STOP);
        assertThat(SmsKeywords.parse("Ayuda")).isEqualTo(SmsKeywords.Keyword.HELP);
        assertThat(SmsKeywords.parse("approve my loan now")).isEqualTo(SmsKeywords.Keyword.UNKNOWN);
    }

    @Test
    void tcApi08_zapierSignaturesMustMatchAndBeFresh() {
        ZapierSmsClient zapier = new ZapierSmsClient(new ObjectMapper(), "", "secret-for-tests");
        String now = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"from\":\"+14155550123\",\"text\":\"STOP\"}";
        String good = zapier.sign(now + "." + body);

        assertThat(zapier.verify(now, good, body)).isTrue();
        assertThat(zapier.verify(now, good, body.replace("STOP", "START"))).isFalse();
        String old = String.valueOf(Instant.now().minusSeconds(3_600).getEpochSecond());
        assertThat(zapier.verify(old, zapier.sign(old + "." + body), body)).isFalse();
        assertThat(zapier.verify(null, good, body)).isFalse();
    }
}
