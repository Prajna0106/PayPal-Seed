package com.paypalseed.payments.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.payments.api.dto.PaymentsDtos.RecipientType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The keyed fingerprint of a payout recipient (ADR 0027). */
class RecipientFingerprintsTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef-key-one";
    private final RecipientFingerprints fingerprints = new RecipientFingerprints(KEY.getBytes(StandardCharsets.UTF_8));

    private static MockEnvironment profiles(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    @Test
    void theSameRecipientHasTheSameFingerprintWhateverTheSpelling() {
        String once = fingerprints.of(RecipientType.EMAIL, "Ana.Perez@Example.test");

        assertThat(fingerprints.of(RecipientType.EMAIL, "  ana.perez@example.TEST ")).isEqualTo(once);
        assertThat(fingerprints.of(RecipientType.PHONE, "+1 (555) 123-0000")).isEqualTo(fingerprints.of(RecipientType.PHONE, "+15551230000"));
        assertThat(fingerprints.of(RecipientType.PAYPAL_ID, "payer123")).isEqualTo(fingerprints.of(RecipientType.PAYPAL_ID, " PAYER123"));
    }

    @Test
    void anotherRecipientHasAnotherFingerprint() {
        assertThat(fingerprints.of(RecipientType.EMAIL, "ana@example.test")).isNotEqualTo(fingerprints.of(RecipientType.EMAIL, "bo@example.test"));
        assertThat(fingerprints.of(RecipientType.PHONE, "+15551230000")).isNotEqualTo(fingerprints.of(RecipientType.PHONE, "15551230000"));
    }

    @Test
    void theTypeIsPartOfTheIdentity() {
        assertThat(fingerprints.of(RecipientType.EMAIL, "same-text")).isNotEqualTo(fingerprints.of(RecipientType.PAYPAL_ID, "same-text"));
    }

    @Test
    void theFingerprintIs64HexCharactersAndShowsNothingOfTheRecipient() {
        String fingerprint = fingerprints.of(RecipientType.EMAIL, "ana@example.test");

        assertThat(fingerprint).matches("[0-9a-f]{64}").doesNotContain("ana").doesNotContain("example");
    }

    @Test
    void itIsKeyedSoItCannotBeRecomputedWithoutTheSecret() {
        RecipientFingerprints other = new RecipientFingerprints("another-key-another-key-another-key!".getBytes(StandardCharsets.UTF_8));

        assertThat(other.of(RecipientType.EMAIL, "ana@example.test")).isNotEqualTo(fingerprints.of(RecipientType.EMAIL, "ana@example.test"));
    }

    @Test
    void productionRefusesToStartWithoutAKey() {
        assertThatThrownBy(() -> new RecipientFingerprints("", profiles("prod")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("seed.payments.recipient-fingerprint-key")
            .hasMessageContaining("SEED_PAYMENTS_RECIPIENT_FINGERPRINT_KEY");
        assertThatThrownBy(() -> new RecipientFingerprints(" ", profiles())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aShortKeyIsRefusedEverywhereAndTheMessageDoesNotRepeatIt() {
        assertThatThrownBy(() -> new RecipientFingerprints("too-short", profiles("dev")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 32").hasMessageNotContaining("too-short");
    }

    @Test
    void developmentTestAndDemoGetARandomKeyPerProcess() {
        for (String profile : new String[] {"dev", "test", "demo"}) {
            RecipientFingerprints first = new RecipientFingerprints("", profiles(profile));
            RecipientFingerprints second = new RecipientFingerprints("", profiles(profile));

            assertThat(first.of(RecipientType.EMAIL, "ana@example.test")).as(profile).isEqualTo(first.of(RecipientType.EMAIL, "ana@example.test"));
            assertThat(first.of(RecipientType.EMAIL, "ana@example.test")).as(profile).isNotEqualTo(second.of(RecipientType.EMAIL, "ana@example.test"));
        }
    }

    @Test
    void aConfiguredKeyIsUsedInEveryProfile() {
        RecipientFingerprints configured = new RecipientFingerprints(KEY, profiles("prod"));

        assertThat(configured.of(RecipientType.EMAIL, "ana@example.test")).isEqualTo(fingerprints.of(RecipientType.EMAIL, "ana@example.test"));
    }
}
