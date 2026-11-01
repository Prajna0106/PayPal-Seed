package com.paypalseed.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.identity.api.dto.IdentityDtos.PayoutRecipient;
import com.paypalseed.identity.api.dto.WalletPreference;
import org.junit.jupiter.api.Test;

class FraudFeedTest {

    private static final PayoutRecipient RECIPIENT = new PayoutRecipient("PAYER-1", "ana@example.test");

    @Test
    void aPayPalWalletPayoutGoesToThePayerIdAndVenmoToTheEmail() {
        assertThat(FraudFeed.reference(WalletPreference.PAYPAL, RECIPIENT)).isEqualTo("PAYPAL:PAYER-1");
        assertThat(FraudFeed.reference(WalletPreference.VENMO, RECIPIENT)).isEqualTo("VENMO:ana@example.test");
    }

    @Test
    void venmoWithoutAnEmailFallsBackToThePayerIdAndNoPreferenceMeansPayPal() {
        assertThat(FraudFeed.reference(WalletPreference.VENMO, new PayoutRecipient("PAYER-1", null))).isEqualTo("VENMO:PAYER-1");
        assertThat(FraudFeed.reference(WalletPreference.VENMO, new PayoutRecipient("PAYER-1", " "))).isEqualTo("VENMO:PAYER-1");
        assertThat(FraudFeed.reference(null, RECIPIENT)).isEqualTo("PAYPAL:PAYER-1");
    }

    @Test
    void theSameDestinationAlwaysGivesTheSameReference() {
        assertThat(FraudFeed.reference(WalletPreference.PAYPAL, RECIPIENT))
                .isEqualTo(FraudFeed.reference(WalletPreference.PAYPAL, new PayoutRecipient("PAYER-1", "other@example.test")));
    }
}
