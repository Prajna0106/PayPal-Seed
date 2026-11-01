package com.paypalseed.underwriting.infrastructure.persistence;

import static com.paypalseed.underwriting.infrastructure.persistence.jooq.Tables.FAIR_LOAN_CHECK_ASSESSMENT;
import static com.paypalseed.underwriting.infrastructure.persistence.jooq.Tables.INCOME_CONSENT;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.engines.assessment.AssessmentEngine.FairCheckResult;
import com.paypalseed.engines.rules.LoanTier;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment;
import com.paypalseed.underwriting.domain.model.FairLoanCheckAssessment.OfficerDecisionType;
import com.paypalseed.underwriting.infrastructure.persistence.jooq.tables.records.FairLoanCheckAssessmentRecord;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * Persistence store for member fair-check consents and auditable {@link FairLoanCheckAssessment} records.
 * Uses jOOQ + PostgreSQL in production/integration tests and falls back to an in-memory map in standalone unit tests.
 */
@Repository
public class UnderwritingRepository {

    public record ConsentRecord(UUID memberId, boolean consented, Instant updatedAt) {
    }

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final DSLContext dsl;
    private final ObjectMapper json;
    private final Map<UUID, ConsentRecord> consents = new ConcurrentHashMap<>();
    private final Map<UUID, FairLoanCheckAssessment> assessments = new ConcurrentHashMap<>();

    public UnderwritingRepository() {
        this.dsl = null;
        this.json = new ObjectMapper();
    }

    @Autowired
    public UnderwritingRepository(DSLContext dsl, @Autowired(required = false) ObjectMapper json) {
        this.dsl = dsl;
        this.json = json != null ? json : new ObjectMapper();
    }

    public ConsentRecord saveConsent(UUID memberId, boolean consented, Instant now) {
        Objects.requireNonNull(memberId, "memberId is required");
        Objects.requireNonNull(now, "now is required");
        ConsentRecord rec = new ConsentRecord(memberId, consented, now);
        if (dsl == null) {
            consents.put(memberId, rec);
            return rec;
        }

        if (consented) {
            dsl.insertInto(INCOME_CONSENT)
                .set(INCOME_CONSENT.MEMBER_ID, memberId)
                .set(INCOME_CONSENT.GRANTED_AT, now)
                .set(INCOME_CONSENT.REVOKED_AT, DSL.castNull(INCOME_CONSENT.REVOKED_AT))
                .set(INCOME_CONSENT.UPDATED_AT, now)
                .onConflict(INCOME_CONSENT.MEMBER_ID).doUpdate()
                .set(INCOME_CONSENT.GRANTED_AT, now)
                .set(INCOME_CONSENT.REVOKED_AT, DSL.castNull(INCOME_CONSENT.REVOKED_AT))
                .set(INCOME_CONSENT.UPDATED_AT, now)
                .execute();
        } else {
            dsl.insertInto(INCOME_CONSENT)
                .set(INCOME_CONSENT.MEMBER_ID, memberId)
                .set(INCOME_CONSENT.GRANTED_AT, now)
                .set(INCOME_CONSENT.REVOKED_AT, now)
                .set(INCOME_CONSENT.UPDATED_AT, now)
                .onConflict(INCOME_CONSENT.MEMBER_ID).doUpdate()
                .set(INCOME_CONSENT.REVOKED_AT, now)
                .set(INCOME_CONSENT.UPDATED_AT, now)
                .execute();
        }
        return rec;
    }

    public Optional<ConsentRecord> findConsent(UUID memberId) {
        if (dsl == null) {
            return Optional.ofNullable(consents.get(memberId));
        }
        return dsl.select(INCOME_CONSENT.MEMBER_ID, INCOME_CONSENT.REVOKED_AT, INCOME_CONSENT.UPDATED_AT)
            .from(INCOME_CONSENT)
            .where(INCOME_CONSENT.MEMBER_ID.eq(memberId))
            .fetchOptional(r -> new ConsentRecord(r.value1(), r.value2() == null, r.value3()));
    }

    public FairLoanCheckAssessment saveAssessment(FairLoanCheckAssessment a) {
        Objects.requireNonNull(a, "assessment is required");
        if (dsl == null) {
            assessments.put(a.getId(), a);
            return a;
        }

        FairCheckResult r = a.getResult();
        dsl.insertInto(FAIR_LOAN_CHECK_ASSESSMENT)
            .set(FAIR_LOAN_CHECK_ASSESSMENT.ID, a.getId())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.MEMBER_ID, a.getMemberId())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.APPLICATION_ID, a.getApplicationId())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.PURPOSE, a.getPurpose())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.RECOMMENDED, r.recommended())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.UNLOCKED_TIER, r.unlockedTier().name())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.USED_FALLBACK_STATED_INCOME, r.usedFallbackStatedIncome())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.AVERAGE_MONTHLY_INCOME_CENTS, r.averageMonthlyIncomeCents())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.LOWEST_MONTHLY_INCOME_CENTS, r.lowestMonthlyIncomeCents())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.LOWEST_INCOME_MONTH, r.lowestIncomeMonth())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.COEFFICIENT_OF_VARIATION, r.coefficientOfVariation())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.HIGH_INCOME_VOLATILITY, r.highIncomeVolatility())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.PROVEN_MONTHLY_SAVING_CENTS, r.provenMonthlySavingCents())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.MONTHS_SAVED, r.monthsSaved())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.TARGET_MONTHS_WINDOW, r.targetMonthsWindow())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.INSTALMENT_CAP_CENTS, r.instalmentCapCents())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.REQUESTED_AMOUNT_CENTS, r.requestedAmountCents())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.REQUESTED_TERM_MONTHS, r.requestedTermMonths())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.SUGGESTED_LOAN_CENTS, r.suggestedLoanCents())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.SUGGESTED_TERM_MONTHS, r.suggestedTermMonths())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.SUGGESTED_MONTHLY_INSTALMENT_CENTS, r.suggestedMonthlyInstalmentCents())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.INPUTS_HASH, r.inputsHash())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.MODEL_VERSION, a.getModelVersion())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.PLAIN_LANGUAGE_REASONS, toJsonb(r.plainLanguageReasons()))
            .set(FAIR_LOAN_CHECK_ASSESSMENT.OFFICER_QUESTIONS, toJsonb(r.officerQuestions()))
            .set(FAIR_LOAN_CHECK_ASSESSMENT.USED_DATA, toJsonb(r.usedData()))
            .set(FAIR_LOAN_CHECK_ASSESSMENT.NEVER_USED_DATA, toJsonb(r.neverUsedData()))
            .set(FAIR_LOAN_CHECK_ASSESSMENT.MEMBER_DISPUTED, a.isMemberDisputed())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.MEMBER_DISPUTE_NOTE, a.getMemberDisputeNote())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.DISPUTED_AT, a.getDisputedAt())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.OFFICER_DECISION, a.getOfficerDecision().name())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.OFFICER_ID, a.getOfficerId())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.DECIDED_LOAN_CENTS, a.getDecidedLoanCents())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.DECIDED_TERM_MONTHS, a.getDecidedTermMonths())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.OFFICER_REASON, a.getOfficerReason())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.DECIDED_AT, a.getDecidedAt())
            .set(FAIR_LOAN_CHECK_ASSESSMENT.CREATED_AT, a.getCreatedAt())
            .onConflict(FAIR_LOAN_CHECK_ASSESSMENT.ID).doUpdate()
            .set(FAIR_LOAN_CHECK_ASSESSMENT.MEMBER_DISPUTED, DSL.excluded(FAIR_LOAN_CHECK_ASSESSMENT.MEMBER_DISPUTED))
            .set(FAIR_LOAN_CHECK_ASSESSMENT.MEMBER_DISPUTE_NOTE, DSL.excluded(FAIR_LOAN_CHECK_ASSESSMENT.MEMBER_DISPUTE_NOTE))
            .set(FAIR_LOAN_CHECK_ASSESSMENT.DISPUTED_AT, DSL.excluded(FAIR_LOAN_CHECK_ASSESSMENT.DISPUTED_AT))
            .set(FAIR_LOAN_CHECK_ASSESSMENT.OFFICER_DECISION, DSL.excluded(FAIR_LOAN_CHECK_ASSESSMENT.OFFICER_DECISION))
            .set(FAIR_LOAN_CHECK_ASSESSMENT.OFFICER_ID, DSL.excluded(FAIR_LOAN_CHECK_ASSESSMENT.OFFICER_ID))
            .set(FAIR_LOAN_CHECK_ASSESSMENT.DECIDED_LOAN_CENTS, DSL.excluded(FAIR_LOAN_CHECK_ASSESSMENT.DECIDED_LOAN_CENTS))
            .set(FAIR_LOAN_CHECK_ASSESSMENT.DECIDED_TERM_MONTHS, DSL.excluded(FAIR_LOAN_CHECK_ASSESSMENT.DECIDED_TERM_MONTHS))
            .set(FAIR_LOAN_CHECK_ASSESSMENT.OFFICER_REASON, DSL.excluded(FAIR_LOAN_CHECK_ASSESSMENT.OFFICER_REASON))
            .set(FAIR_LOAN_CHECK_ASSESSMENT.DECIDED_AT, DSL.excluded(FAIR_LOAN_CHECK_ASSESSMENT.DECIDED_AT))
            .execute();

        return a;
    }

    public Optional<FairLoanCheckAssessment> findAssessmentById(UUID id) {
        if (dsl == null) {
            return Optional.ofNullable(assessments.get(id));
        }
        return dsl.selectFrom(FAIR_LOAN_CHECK_ASSESSMENT)
            .where(FAIR_LOAN_CHECK_ASSESSMENT.ID.eq(id))
            .fetchOptional(this::mapAssessment);
    }

    public List<FairLoanCheckAssessment> findAssessmentsByMemberId(UUID memberId) {
        if (dsl == null) {
            return assessments.values().stream()
                .filter(a -> a.getMemberId().equals(memberId))
                .sorted(Comparator.comparing(FairLoanCheckAssessment::getCreatedAt).reversed())
                .toList();
        }
        return dsl.selectFrom(FAIR_LOAN_CHECK_ASSESSMENT)
            .where(FAIR_LOAN_CHECK_ASSESSMENT.MEMBER_ID.eq(memberId))
            .orderBy(FAIR_LOAN_CHECK_ASSESSMENT.CREATED_AT.desc())
            .fetch(this::mapAssessment);
    }

    public List<FairLoanCheckAssessment> findAllAssessments() {
        if (dsl == null) {
            return assessments.values().stream()
                .sorted(Comparator.comparing(FairLoanCheckAssessment::getCreatedAt).reversed())
                .toList();
        }
        return dsl.selectFrom(FAIR_LOAN_CHECK_ASSESSMENT)
            .orderBy(FAIR_LOAN_CHECK_ASSESSMENT.CREATED_AT.desc())
            .fetch(this::mapAssessment);
    }

    private FairLoanCheckAssessment mapAssessment(FairLoanCheckAssessmentRecord r) {
        FairCheckResult result = new FairCheckResult(
            Boolean.TRUE.equals(r.getRecommended()),
            LoanTier.valueOf(r.getUnlockedTier()),
            Boolean.TRUE.equals(r.getUsedFallbackStatedIncome()),
            r.getAverageMonthlyIncomeCents(),
            r.getLowestMonthlyIncomeCents(),
            r.getLowestIncomeMonth(),
            r.getCoefficientOfVariation(),
            Boolean.TRUE.equals(r.getHighIncomeVolatility()),
            r.getProvenMonthlySavingCents(),
            r.getMonthsSaved(),
            r.getTargetMonthsWindow(),
            r.getInstalmentCapCents(),
            r.getRequestedAmountCents(),
            r.getRequestedTermMonths(),
            r.getSuggestedLoanCents(),
            r.getSuggestedTermMonths(),
            r.getSuggestedMonthlyInstalmentCents(),
            r.getInputsHash(),
            fromJsonb(r.getPlainLanguageReasons()),
            fromJsonb(r.getOfficerQuestions()),
            fromJsonb(r.getUsedData()),
            fromJsonb(r.getNeverUsedData())
        );

        FairLoanCheckAssessment a = new FairLoanCheckAssessment(
            r.getId(),
            r.getMemberId(),
            r.getApplicationId(),
            r.getPurpose(),
            result,
            r.getModelVersion(),
            r.getCreatedAt()
        );
        a.restoreReviewState(
            Boolean.TRUE.equals(r.getMemberDisputed()),
            r.getMemberDisputeNote(),
            r.getDisputedAt(),
            OfficerDecisionType.valueOf(r.getOfficerDecision()),
            r.getOfficerId(),
            r.getDecidedLoanCents(),
            r.getDecidedTermMonths(),
            r.getOfficerReason(),
            r.getDecidedAt()
        );
        return a;
    }

    private JSONB toJsonb(List<String> values) {
        try {
            return JSONB.valueOf(json.writeValueAsString(values != null ? values : List.of()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize assessment JSONB column", e);
        }
    }

    private List<String> fromJsonb(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonb.data(), STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize assessment JSONB column", e);
        }
    }
}
