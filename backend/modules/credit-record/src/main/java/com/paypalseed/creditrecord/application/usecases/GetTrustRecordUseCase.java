package com.paypalseed.creditrecord.application.usecases;

import com.paypalseed.creditrecord.api.TrustRecordNarrator;
import com.paypalseed.creditrecord.api.TrustRecordNarrator.TrustNarrationInput;
import com.paypalseed.creditrecord.api.TrustRecordNarrator.TrustNarrationResult;
import com.paypalseed.platform.kernel.BusinessClock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GetTrustRecordUseCase {

    private final TrustRecordNarrator narrator;
    private final BusinessClock clock;

    public record TrustRecordResponse(
        UUID memberId,
        String currentTier,
        String nextTier,
        long currentLimitCents,
        long nextLimitCents,
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

    public GetTrustRecordUseCase(@Autowired(required = false) TrustRecordNarrator narrator, BusinessClock clock) {
        this.narrator = narrator;
        this.clock = clock;
    }

    public TrustRecordResponse getTrustRecord(UUID memberId) {
        String today = clock.today().toString();
        String currentTier = "LEVEL_1";
        String nextTier = "LEVEL_2";
        long currentLimit = 120000L;
        long nextLimit = 240000L;
        int onTimeRepayments = 8;
        int streakMonths = 4;
        long totalSaved = 30000L;
        long totalRepaid = 120000L;

        String templateSummaryEn = "You are currently at Level 1 with 8 on-time repayments and $300 in verified savings. Unlocking Level 2 increases your credit limit to $2,400.";
        String templateSummaryEs = "Actualmente estás en el Nivel 1 con 8 pagos puntuales y $300 en ahorros verificados. El Nivel 2 aumentará tu límite de crédito a $2,400.";
        String templateAttestation = "Community Credit Standing: Member has completed 8 consecutive on-time repayments totaling $1,200 with zero default events.";
        String templateAdvice = "Complete your current microloan repayment schedule to graduate to Level 2 ($2,400 limit).";

        TrustNarrationInput input = new TrustNarrationInput(
            memberId.toString(),
            currentTier,
            nextTier,
            currentLimit,
            nextLimit,
            onTimeRepayments,
            streakMonths,
            totalSaved,
            totalRepaid,
            templateSummaryEn,
            templateSummaryEs,
            templateAttestation,
            templateAdvice
        );

        TrustNarrationResult result;
        if (narrator != null) {
            result = narrator.narrate(input);
        } else {
            result = new TrustNarrationResult(templateSummaryEn, templateSummaryEs, templateAttestation, templateAdvice, false);
        }

        String rawFacts = memberId + ":" + currentTier + ":" + currentLimit + ":" + onTimeRepayments + ":" + totalRepaid + ":" + today;
        String verificationHash = sha256Hex(rawFacts);

        return new TrustRecordResponse(
            memberId,
            currentTier,
            nextTier,
            currentLimit,
            nextLimit,
            onTimeRepayments,
            streakMonths,
            totalSaved,
            totalRepaid,
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
