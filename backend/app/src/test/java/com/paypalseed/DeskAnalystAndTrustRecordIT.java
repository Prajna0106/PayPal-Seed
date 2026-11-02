package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paypalseed.support.IntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test verifying the Phase 2 & Phase 3 agent endpoints:
 * 1. GET /api/v1/desk/analyst/briefing (Desk Analyst Agent)
 * 2. GET /api/v1/members/me/trust-record (Trust Agent portable credit record)
 */
class DeskAnalystAndTrustRecordIT extends IntegrationTest {

    private UUID memberId;
    private UUID staffId;

    @BeforeEach
    void setup() {
        memberId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        clock.set(LocalDate.of(2026, 11, 2));
    }

    @Test
    void deskAnalystBriefingRequiresStaffOfficerRole() throws Exception {
        // Without staff header -> 401
        mvc.perform(get("/api/v1/desk/analyst/briefing"))
            .andExpect(status().isUnauthorized());

        // As an officer -> 200 with full briefing
        mvc.perform(get("/api/v1/desk/analyst/briefing")
                .header("X-Seed-Staff-Id", staffId.toString())
                .header("X-Seed-Staff-Roles", "OFFICER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.businessDate").value("2026-11-02"))
            .andExpect(jsonPath("$.activeLoansCount").value(142))
            .andExpect(jsonPath("$.totalOutstandingCents").value(17040000))
            .andExpect(jsonPath("$.healthyLoansCount").value(135))
            .andExpect(jsonPath("$.atRiskLoansCount").value(7))
            .andExpect(jsonPath("$.casesPendingReviewCount").value(4))
            .andExpect(jsonPath("$.executiveSummary").isNotEmpty())
            .andExpect(jsonPath("$.queuePriorities").isArray())
            .andExpect(jsonPath("$.riskObservations").isArray())
            .andExpect(jsonPath("$.recommendedStaffingFocus").isNotEmpty());
    }

    @Test
    void trustRecordRequiresMemberAuthAndReturnsGroundedCertificate() throws Exception {
        // Without member header -> 401
        mvc.perform(get("/api/v1/members/me/trust-record"))
            .andExpect(status().isUnauthorized());

        // As authenticated member -> 200 with cryptographic attestation
        mvc.perform(get("/api/v1/members/me/trust-record")
                .header("X-Seed-Member-Id", memberId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memberId").value(memberId.toString()))
            .andExpect(jsonPath("$.currentTier").value("LEVEL_1"))
            .andExpect(jsonPath("$.nextTier").value("LEVEL_2"))
            .andExpect(jsonPath("$.currentLimitCents").value(120000))
            .andExpect(jsonPath("$.nextLimitCents").value(240000))
            .andExpect(jsonPath("$.onTimeRepaymentsCount").value(8))
            .andExpect(jsonPath("$.streakMonths").value(4))
            .andExpect(jsonPath("$.totalSavedCents").value(30000))
            .andExpect(jsonPath("$.totalRepaidCents").value(120000))
            .andExpect(jsonPath("$.summaryEn").isNotEmpty())
            .andExpect(jsonPath("$.summaryEs").isNotEmpty())
            .andExpect(jsonPath("$.certificateAttestation").isNotEmpty())
            .andExpect(jsonPath("$.milestoneAdvice").isNotEmpty())
            .andExpect(jsonPath("$.verificationHash").isString())
            .andExpect(jsonPath("$.issuedAt").value("2026-11-02"));
    }
}
