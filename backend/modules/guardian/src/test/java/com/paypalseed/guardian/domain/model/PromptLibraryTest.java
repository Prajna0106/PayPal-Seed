package com.paypalseed.guardian.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PromptLibraryTest {

    /** A live model copied dropBps (3168) into a PERCENT figure and wrote "3168 basis points", which V06 rejects. */
    @Test
    void narrativePromptTellsTheModelToTurnBasisPointsIntoWholePercent() {
        String narrative = PromptLibrary.load().narrative();

        assertThat(narrative).contains("Bps").contains("basis points").contains("whole percent");
    }
}
