package com.paypalseed.creditrecord.application.usecases;

import com.paypalseed.creditrecord.api.TrustRecordFigures;
import com.paypalseed.creditrecord.api.TrustRecordFigures.MemberFigures;
import com.paypalseed.creditrecord.api.TrustRecordNarrator;
import com.paypalseed.creditrecord.api.TrustRecordNarrator.TrustNarrationInput;
import com.paypalseed.creditrecord.api.TrustRecordNarrator.TrustNarrationResult;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.RecordView;
import com.paypalseed.engines.rules.record.RecordLevel;
import com.paypalseed.platform.kernel.BusinessClock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The signed-in member's trust record: their own credit-record level and counts (ADR 0014), with the saving and repayment
 * totals the wiring module reads from savings and lending, in the member's words. The caller passes the member from the
 * session and nothing here takes another id, so one member's figures can never carry another's. The narrator may only
 * reword; the figures and the verification hash come from code.
 */
@Service
public class GetTrustRecordUseCase {

    /** What the narrator is told when the member is already at the top level and there is no next one. */
    static final String NO_NEXT_TIER = "NONE";

    private final TrustRecordNarrator narrator;
    private final RecordQueries records;
    private final TrustRecordFigures figures;
    private final BusinessClock clock;

    /**
     * {@code currentTier} and {@code nextTier} are the record's own level names ({@code NEW} to {@code EXEMPLARY});
     * {@code nextTier} and {@code nextLimitCents} are null at the top level. {@code onTimeRepaymentsCount} counts instalments
     * paid on time over the whole record.
     */
    public record TrustRecordResponse(
        UUID memberId,
        String currentTier,
        String nextTier,
        long currentLimitCents,
        Long nextLimitCents,
        int onTimeRepaymentsCount,
        int streakMonths,
        long totalSavedCents,
        long totalRepaidCents,
        String summaryEn,
        String summaryEs,
        String certificateAttestation,
        String milestoneAdvice,
        String verificationHash,
        String issuedAt,
        boolean modelGenerated
    ) {}

    public GetTrustRecordUseCase(@Autowired(required = false) TrustRecordNarrator narrator, RecordQueries records,
                                 TrustRecordFigures figures, BusinessClock clock) {
        this.narrator = narrator;
        this.records = records;
        this.figures = figures;
        this.clock = clock;
    }

    public TrustRecordResponse getTrustRecord(UUID memberId) {
        String today = clock.today().toString();
        RecordView view = records.record(memberId);
        MemberFigures money = figures.of(memberId);
        RecordLevel level = RecordLevel.valueOf(view.level());
        Optional<RecordLevel> next = level.next();

        String currentTier = level.name();
        String nextTier = next.map(RecordLevel::name).orElse(null);
        long currentLimit = level.loanLimitCents();
        Long nextLimit = next.map(RecordLevel::loanLimitCents).orElse(null);
        int onTimeRepayments = view.instalments().onTime();

        TrustTemplates template = TrustTemplates.of(view, money.savingStreakMonths(), money.totalSavedCents(),
            money.totalRepaidCents());

        TrustNarrationInput input = new TrustNarrationInput(
            memberId.toString(),
            currentTier,
            nextTier == null ? NO_NEXT_TIER : nextTier,
            currentLimit,
            nextLimit == null ? currentLimit : nextLimit,
            onTimeRepayments,
            money.savingStreakMonths(),
            money.totalSavedCents(),
            money.totalRepaidCents(),
            template.summaryEn(),
            template.summaryEs(),
            template.attestation(),
            template.advice()
        );

        TrustNarrationResult result;
        if (narrator != null) {
            result = narrator.narrate(input);
        } else {
            result = new TrustNarrationResult(template.summaryEn(), template.summaryEs(), template.attestation(),
                template.advice(), false);
        }

        String rawFacts = memberId + ":" + currentTier + ":" + currentLimit + ":" + onTimeRepayments + ":"
            + money.savingStreakMonths() + ":" + money.totalSavedCents() + ":" + money.totalRepaidCents() + ":" + today;
        String verificationHash = sha256Hex(rawFacts);

        return new TrustRecordResponse(
            memberId,
            currentTier,
            nextTier,
            currentLimit,
            nextLimit,
            onTimeRepayments,
            money.savingStreakMonths(),
            money.totalSavedCents(),
            money.totalRepaidCents(),
            result.summaryEn(),
            result.summaryEs(),
            result.certificateAttestation(),
            result.milestoneAdvice(),
            verificationHash,
            today,
            result.modelGenerated()
        );
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
