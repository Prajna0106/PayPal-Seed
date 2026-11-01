package com.paypalseed.lending.infrastructure.persistence;

import static com.paypalseed.lending.infrastructure.persistence.jooq.Tables.LOAN_APPLICATION;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.engines.rules.LoanTier;
import com.paypalseed.lending.application.ports.LoanApplicationRepository;
import com.paypalseed.lending.domain.model.LoanApplication;
import com.paypalseed.lending.domain.model.LoanApplication.ApplicationStatus;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.lending.infrastructure.persistence.jooq.tables.records.LoanApplicationRecord;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * jOOQ-backed PostgreSQL repository for {@link LoanApplication} aggregates (Feature 2).
 */
@Repository
public class JooqLoanApplicationRepository implements LoanApplicationRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final DSLContext dsl;
    private final ObjectMapper json;

    @Autowired
    public JooqLoanApplicationRepository(DSLContext dsl, @Autowired(required = false) ObjectMapper json) {
        this.dsl = Objects.requireNonNull(dsl, "dsl is required");
        this.json = json != null ? json : new ObjectMapper();
    }

    @Override
    public LoanApplication save(LoanApplication app) {
        Objects.requireNonNull(app, "application is required");
        JSONB reasonsJson = toJsonb(app.getSizingReasons());

        dsl.insertInto(LOAN_APPLICATION)
            .set(LOAN_APPLICATION.ID, app.getId())
            .set(LOAN_APPLICATION.MEMBER_ID, app.getMemberId())
            .set(LOAN_APPLICATION.PURPOSE, app.getPurpose())
            .set(LOAN_APPLICATION.REQUESTED_AMOUNT_CENTS, app.getRequestedAmountCents())
            .set(LOAN_APPLICATION.TERM_MONTHS, app.getTermMonths())
            .set(LOAN_APPLICATION.PREFERRED_DUE_DAY, app.getPreferredDueDay())
            .set(LOAN_APPLICATION.PAYOUT_WALLET, app.getPayoutWallet().name())
            .set(LOAN_APPLICATION.FAIR_CHECK_CONSENTED, app.isFairCheckConsented())
            .set(LOAN_APPLICATION.UNLOCKED_TIER, app.getUnlockedTier().name())
            .set(LOAN_APPLICATION.MAX_MONTHLY_INSTALMENT_CENTS, app.getMaxMonthlyInstalmentCents())
            .set(LOAN_APPLICATION.MONTHLY_INSTALMENT_CENTS, app.getMonthlyInstalmentCents())
            .set(LOAN_APPLICATION.SIZING_REASONS, reasonsJson)
            .set(LOAN_APPLICATION.STATUS, app.getStatus().name())
            .set(LOAN_APPLICATION.FIRST_APPROVER_ID, app.getFirstApproverId())
            .set(LOAN_APPLICATION.FIRST_APPROVED_AT, app.getFirstApprovedAt())
            .set(LOAN_APPLICATION.SECOND_APPROVER_ID, app.getSecondApproverId())
            .set(LOAN_APPLICATION.SECOND_APPROVED_AT, app.getSecondApprovedAt())
            .set(LOAN_APPLICATION.REJECTION_REASON, app.getRejectionReason())
            .set(LOAN_APPLICATION.LOAN_ID, app.getLoanId())
            .set(LOAN_APPLICATION.DISBURSEMENT_REF, app.getDisbursementRef())
            .set(LOAN_APPLICATION.DISBURSED_AT, app.getDisbursedAt())
            .set(LOAN_APPLICATION.COOLING_OFF_EXPIRES_AT, app.getCoolingOffExpiresAt())
            .set(LOAN_APPLICATION.CREATED_AT, app.getCreatedAt())
            .set(LOAN_APPLICATION.UPDATED_AT, app.getUpdatedAt())
            .set(LOAN_APPLICATION.PAYOUT_ISSUE, app.getPayoutIssue())
            .set(LOAN_APPLICATION.PAYOUT_ATTEMPTS, app.getPayoutAttempts())
            .set(LOAN_APPLICATION.LAST_OFFICER_ID, app.getLastOfficerId())
            .onConflict(LOAN_APPLICATION.ID).doUpdate()
            .set(LOAN_APPLICATION.STATUS, DSL.excluded(LOAN_APPLICATION.STATUS))
            .set(LOAN_APPLICATION.FIRST_APPROVER_ID, DSL.excluded(LOAN_APPLICATION.FIRST_APPROVER_ID))
            .set(LOAN_APPLICATION.FIRST_APPROVED_AT, DSL.excluded(LOAN_APPLICATION.FIRST_APPROVED_AT))
            .set(LOAN_APPLICATION.SECOND_APPROVER_ID, DSL.excluded(LOAN_APPLICATION.SECOND_APPROVER_ID))
            .set(LOAN_APPLICATION.SECOND_APPROVED_AT, DSL.excluded(LOAN_APPLICATION.SECOND_APPROVED_AT))
            .set(LOAN_APPLICATION.REJECTION_REASON, DSL.excluded(LOAN_APPLICATION.REJECTION_REASON))
            .set(LOAN_APPLICATION.LOAN_ID, DSL.excluded(LOAN_APPLICATION.LOAN_ID))
            .set(LOAN_APPLICATION.DISBURSEMENT_REF, DSL.excluded(LOAN_APPLICATION.DISBURSEMENT_REF))
            .set(LOAN_APPLICATION.DISBURSED_AT, DSL.excluded(LOAN_APPLICATION.DISBURSED_AT))
            .set(LOAN_APPLICATION.COOLING_OFF_EXPIRES_AT, DSL.excluded(LOAN_APPLICATION.COOLING_OFF_EXPIRES_AT))
            .set(LOAN_APPLICATION.UPDATED_AT, DSL.excluded(LOAN_APPLICATION.UPDATED_AT))
            .set(LOAN_APPLICATION.PAYOUT_ISSUE, DSL.excluded(LOAN_APPLICATION.PAYOUT_ISSUE))
            .set(LOAN_APPLICATION.PAYOUT_ATTEMPTS, DSL.excluded(LOAN_APPLICATION.PAYOUT_ATTEMPTS))
            .set(LOAN_APPLICATION.LAST_OFFICER_ID, DSL.excluded(LOAN_APPLICATION.LAST_OFFICER_ID))
            .execute();

        return app;
    }

    @Override
    public Optional<LoanApplication> findById(UUID id) {
        return dsl.selectFrom(LOAN_APPLICATION)
            .where(LOAN_APPLICATION.ID.eq(id))
            .fetchOptional(this::map);
    }

    @Override
    public Optional<LoanApplication> findByIdForUpdate(UUID id) {
        return dsl.selectFrom(LOAN_APPLICATION)
            .where(LOAN_APPLICATION.ID.eq(id))
            .forUpdate()
            .fetchOptional(this::map);
    }

    @Override
    public List<LoanApplication> findByMemberId(UUID memberId) {
        return dsl.selectFrom(LOAN_APPLICATION)
            .where(LOAN_APPLICATION.MEMBER_ID.eq(memberId))
            .orderBy(LOAN_APPLICATION.CREATED_AT.desc())
            .fetch(this::map);
    }

    @Override
    public List<LoanApplication> findAll() {
        return dsl.selectFrom(LOAN_APPLICATION)
            .orderBy(LOAN_APPLICATION.CREATED_AT.desc())
            .fetch(this::map);
    }

    private LoanApplication map(LoanApplicationRecord r) {
        LoanApplication app = new LoanApplication(
            r.getId(),
            r.getMemberId(),
            r.getPurpose(),
            r.getRequestedAmountCents(),
            r.getTermMonths(),
            r.getPreferredDueDay(),
            WalletType.valueOf(r.getPayoutWallet()),
            Boolean.TRUE.equals(r.getFairCheckConsented()),
            LoanTier.valueOf(r.getUnlockedTier()),
            r.getMaxMonthlyInstalmentCents(),
            r.getMonthlyInstalmentCents(),
            fromJsonb(r.getSizingReasons()),
            ApplicationStatus.valueOf(r.getStatus()),
            r.getCreatedAt(),
            r.getUpdatedAt()
        );
        app.restoreLifecycleState(
            r.getFirstApproverId(),
            r.getFirstApprovedAt(),
            r.getSecondApproverId(),
            r.getSecondApprovedAt(),
            r.getRejectionReason(),
            r.getLoanId(),
            r.getDisbursementRef(),
            r.getDisbursedAt(),
            r.getCoolingOffExpiresAt()
        );
        app.restorePayoutState(r.getPayoutIssue(), r.getPayoutAttempts(), r.getLastOfficerId());
        return app;
    }

    private JSONB toJsonb(List<String> values) {
        try {
            return JSONB.valueOf(json.writeValueAsString(values != null ? values : List.of()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize sizing_reasons", e);
        }
    }

    private List<String> fromJsonb(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonb.data(), STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize sizing_reasons", e);
        }
    }
}
