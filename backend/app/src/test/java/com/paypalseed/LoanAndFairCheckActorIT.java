package com.paypalseed;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The loan and fair-check routes know who is calling from the verified actor, not from a header the caller wrote
 * (ADR 0016, ADR 0020): the old {@code X-Member-Id} and {@code X-Staff-Id} are worth nothing, a member id that is not
 * the caller's own is a 404, and every desk route is for staff.
 */
class LoanAndFairCheckActorIT extends MoneyFlowSupport {

    private static final String LOAN_BODY = """
            {"purpose":"Stand mixer","requestedAmountCents":120000,"termMonths":8,"fairCheckConsented":false}""";

    @Test
    void theOldPlainHeadersIdentifyNobody() throws Exception {
        UUID member = newMember();
        UUID officer = UUID.randomUUID();

        mvc.perform(get("/api/v1/loans/eligibility").header("X-Member-Id", member.toString())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/loans/applications").header("X-Member-Id", member.toString())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/loans/applications").header("X-Member-Id", member.toString())
                .contentType(MediaType.APPLICATION_JSON).content(LOAN_BODY)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/underwriting/consent").header("X-Member-Id", member.toString())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/underwriting/consent").header("X-Member-Id", member.toString())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/desk/loans/applications").header("X-Staff-Id", officer.toString())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/desk/underwriting/assessments").header("X-Staff-Id", officer.toString()))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/desk/loans/applications/{id}/approve", UUID.randomUUID()).header("X-Staff-Id", officer.toString()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void aMemberOnlyEverWorksOnTheirOwnData() throws Exception {
        UUID ana = newMember();
        UUID bea = newMember();

        mvc.perform(asMember(get("/api/v1/loans/eligibility"), ana)).andExpect(status().isOk())
            .andExpect(jsonPath("$.memberId").value(ana.toString()));
        mvc.perform(asMember(get("/api/v1/loans/eligibility"), ana).param("memberId", ana.toString())).andExpect(status().isOk());
        mvc.perform(asMember(get("/api/v1/loans/eligibility"), ana).param("memberId", bea.toString())).andExpect(status().isNotFound());
        mvc.perform(asMember(get("/api/v1/loans/applications"), ana).param("memberId", bea.toString())).andExpect(status().isNotFound());
        mvc.perform(asMember(post("/api/v1/loans/applications"), ana)
                .content("{\"memberId\":\"" + bea + "\",\"purpose\":\"Mixer\",\"requestedAmountCents\":120000,\"termMonths\":8}"))
            .andExpect(status().isNotFound());
        mvc.perform(asMember(post("/api/v1/underwriting/consent"), ana).param("memberId", bea.toString()))
            .andExpect(status().isNotFound());
        mvc.perform(asMember(delete("/api/v1/underwriting/consent"), ana).param("memberId", bea.toString()))
            .andExpect(status().isNotFound());
        mvc.perform(asMember(get("/api/v1/underwriting/assessments"), ana).param("memberId", bea.toString()))
            .andExpect(status().isNotFound());

        mvc.perform(asMember(post("/api/v1/underwriting/consent"), ana)).andExpect(status().isOk())
            .andExpect(jsonPath("$.consented").value(true));
        mvc.perform(asMember(get("/api/v1/underwriting/consent"), bea)).andExpect(status().isOk())
            .andExpect(jsonPath("$.consented").value(false));
    }

    @Test
    void theDeskRoutesAreForStaffOnly() throws Exception {
        UUID member = newMember();
        UUID application = UUID.randomUUID();
        UUID assessment = UUID.randomUUID();
        UUID officer = UUID.randomUUID();

        mvc.perform(asMember(get("/api/v1/desk/loans/applications"), member)).andExpect(status().isForbidden());
        mvc.perform(asMember(post("/api/v1/desk/loans/applications/{id}/approve", application), member)).andExpect(status().isForbidden());
        mvc.perform(asMember(post("/api/v1/desk/loans/applications/{id}/reject", application), member)).andExpect(status().isForbidden());
        mvc.perform(asMember(post("/api/v1/desk/loans/applications/{id}/disburse", application), member)).andExpect(status().isForbidden());
        mvc.perform(asMember(get("/api/v1/desk/underwriting/assessments"), member)).andExpect(status().isForbidden());
        mvc.perform(asMember(post("/api/v1/desk/underwriting/assessments/{id}/decision", assessment), member)
                .content("{\"decision\":\"APPROVED\",\"reason\":\"I trust myself\"}"))
            .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/desk/loans/applications")).andExpect(status().isUnauthorized());
        mvc.perform(asStaff(get("/api/v1/desk/loans/applications"), officer, "")).andExpect(status().isForbidden());
        mvc.perform(asStaff(get("/api/v1/desk/loans/applications"), officer, "OFFICER")).andExpect(status().isOk());
        mvc.perform(asStaff(get("/api/v1/desk/underwriting/assessments"), officer, "CHECKER")).andExpect(status().isOk());
        mvc.perform(asStaff(post("/api/v1/desk/loans/applications/{id}/approve", application), officer, "OFFICER"))
            .andExpect(status().isNotFound());
    }

    @Test
    void anApplicationIsReadByItsOwnerOrByStaffAndByNobodyElse() throws Exception {
        UUID ana = newMember();
        UUID bea = newMember();
        UUID officer = UUID.randomUUID();
        UUID missing = UUID.randomUUID();

        mvc.perform(asMember(get("/api/v1/loans/applications/{id}", missing), ana)).andExpect(status().isNotFound());
        mvc.perform(asStaff(get("/api/v1/loans/applications/{id}", missing), officer, "OFFICER")).andExpect(status().isNotFound());
        mvc.perform(asStaff(get("/api/v1/loans/applications/{id}", missing), officer, "")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/loans/applications/{id}", missing)).andExpect(status().isUnauthorized());
        mvc.perform(asMember(get("/api/v1/underwriting/assessments/{id}", missing), bea)).andExpect(status().isNotFound());
        mvc.perform(asStaff(get("/api/v1/underwriting/assessments/{id}", missing), officer, "")).andExpect(status().isForbidden());
    }
}
