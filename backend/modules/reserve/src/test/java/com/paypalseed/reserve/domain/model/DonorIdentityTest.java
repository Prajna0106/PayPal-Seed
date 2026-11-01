package com.paypalseed.reserve.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DonorIdentityTest {

    @Test
    void aPlainNameIsKept() {
        assertThat(DonorName.clean("Maria")).contains("Maria");
        assertThat(DonorName.clean("  Ana   María  ")).contains("Ana María");
        assertThat(DonorName.clean("O'Brien-Smith Jr.")).contains("O'Brien-Smith Jr.");
    }

    @Test
    void markupLinksAndControlCharactersAreDropped() {
        assertThat(DonorName.clean("<b>Tom</b>")).contains("Tom");
        assertThat(DonorName.clean("Luz <i>Díaz</i>")).contains("Luz Díaz");
        assertThat(DonorName.clean("<script>alert(1)</script>Ana")).contains("alert1 Ana");
        assertThat(DonorName.clean("Tom\n\t Lee\u0000")).contains("Tom Lee");
        assertThat(DonorName.clean("a@b.com")).contains("ab.com");
        assertThat(DonorName.clean("https://x.y/z")).contains("httpsx.yz");
    }

    @Test
    void nothingLeftMeansNoName() {
        assertThat(DonorName.clean(null)).isEmpty();
        assertThat(DonorName.clean("")).isEmpty();
        assertThat(DonorName.clean("   ")).isEmpty();
        assertThat(DonorName.clean("<>/\\@#$%")).isEmpty();
    }

    @Test
    void longNamesAreCutAtTheLimit() {
        String cleaned = DonorName.clean("x".repeat(200)).orElseThrow();
        assertThat(cleaned).hasSize(DonorName.MAX_LENGTH);
        String spaced = DonorName.clean("ab ".repeat(40)).orElseThrow();
        assertThat(spaced.length()).isLessThanOrEqualTo(DonorName.MAX_LENGTH);
        assertThat(spaced).doesNotEndWith(" ");
    }

    @Test
    void customIdsRoundTripAndRejectLookalikes() {
        UUID id = UUID.fromString("0197f3a4-1b2c-7d3e-8f40-5a6b7c8d9e10");
        String custom = DonationCustomId.format(id);
        assertThat(custom).isEqualTo("reserve-donation:0197f3a4-1b2c-7d3e-8f40-5a6b7c8d9e10");
        assertThat(DonationCustomId.parse(custom)).contains(id);
        assertThat(DonationCustomId.parse("loan:" + id)).isEmpty();
        assertThat(DonationCustomId.parse(custom + "x")).isEmpty();
        assertThat(DonationCustomId.parse(custom.toUpperCase())).isEmpty();
        assertThat(DonationCustomId.parse("reserve-donation:not-a-uuid")).isEmpty();
        assertThat(DonationCustomId.parse(null)).isEmpty();
    }

    @Test
    void requestHashesAreStableAndSensitiveToEveryPart() {
        assertThat(RequestHashes.of("a", 1L, null)).isEqualTo(RequestHashes.of("a", 1L, null));
        assertThat(RequestHashes.of("a", 1L, null)).isNotEqualTo(RequestHashes.of("a", 2L, null));
        assertThat(RequestHashes.of("ab", "c")).isNotEqualTo(RequestHashes.of("a", "bc"));
        assertThat(RequestHashes.of("x")).hasSize(64);
    }
}
