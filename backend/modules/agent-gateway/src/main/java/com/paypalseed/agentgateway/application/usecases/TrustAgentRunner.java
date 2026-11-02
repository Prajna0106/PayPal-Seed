package com.paypalseed.agentgateway.application.usecases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.agentgateway.api.AgentRuns;
import com.paypalseed.agentgateway.api.KillSwitch;
import com.paypalseed.agentgateway.api.KillSwitch.Mode;
import com.paypalseed.agentgateway.api.ModelGateway;
import com.paypalseed.agentgateway.api.ModelUnavailableException;
import com.paypalseed.agentgateway.api.llm.ChatMessage;
import com.paypalseed.agentgateway.api.llm.ModelRequest;
import com.paypalseed.agentgateway.api.llm.ModelResponse;
import com.paypalseed.creditrecord.api.TrustRecordNarrator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Feature 6 Portable Credit Record & Community Standing narrator (`trust-agent`, ADR 0009 & ADR 0010).
 *
 * <p>Translates deterministic tier status, on-time repayments, and streak data into member explanations
 * and a third-party certificate attestation, strictly verified against calculated amounts and content guardrails.
 */
@Service
public class TrustAgentRunner implements TrustRecordNarrator {

    public static final String AGENT = "trust-agent";
    public static final String SCHEMA_NAME = "trust_record_narration";
    public static final String SCHEMA = """
        {"type":"object","additionalProperties":false,\
        "required":["summaryEn","summaryEs","certificateAttestation","milestoneAdvice"],\
        "properties":{"summaryEn":{"type":"string"},"summaryEs":{"type":"string"},\
        "certificateAttestation":{"type":"string"},"milestoneAdvice":{"type":"string"}}}""";

    private static final Logger log = LoggerFactory.getLogger(TrustAgentRunner.class);
    private static final int MAX_OUTPUT_TOKENS = 500;
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern DOLLAR_AMOUNT = Pattern.compile("\\$[0-9][0-9,]*(?:\\.[0-9]{1,2})?");

    private static final String SYSTEM_PROMPT = loadPrompt("/trust/prompts/v1/narrate.md");
    public static final String PROMPT_HASH = "sha256:" + sha256Hex(SYSTEM_PROMPT + "\u0000" + SCHEMA).substring(0, 16);

    private final ModelGateway models;
    private final AgentRuns runs;
    private final KillSwitch killSwitch;
    private final ObjectMapper json;

    public TrustAgentRunner(ModelGateway models, AgentRuns runs, KillSwitch killSwitch, ObjectMapper json) {
        this.models = models;
        this.runs = runs;
        this.killSwitch = killSwitch;
        this.json = json;
    }

    @Override
    public TrustNarrationResult narrate(TrustNarrationInput input) {
        TrustNarrationResult fallback = new TrustNarrationResult(
            input.templateSummaryEn(),
            input.templateSummaryEs(),
            input.templateAttestation(),
            input.templateMilestoneAdvice(),
            false
        );
        Mode mode = killSwitch.mode(AGENT);
        UUID runId = runs.start(AGENT, null, null, PROMPT_HASH, "NARRATIVE");
        if (mode == Mode.TEMPLATES_ONLY) {
            runs.finish(runId, "NOT_RUN", "TEMPLATES_ONLY", "AGENT_DISABLED");
            return fallback;
        }

        try {
            ModelResponse response = models.complete(new ModelRequest(
                AGENT,
                runId,
                "NARRATIVE",
                List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(buildUserContext(input))),
                List.of(),
                SCHEMA_NAME,
                SCHEMA,
                MAX_OUTPUT_TOKENS,
                CALL_TIMEOUT
            ));
            runs.recordCall(runId, response);

            TrustNarrationResult parsed = parseAndValidate(response.content(), input);
            if (parsed == null) {
                killSwitch.recordGroundingFailure(AGENT);
                runs.finish(runId, "FAILED_FELL_BACK", "TEMPLATE_FALLBACK", "UNGROUNDED_OR_INVALID");
                return fallback;
            }

            if (mode == Mode.SHADOW) {
                runs.finish(runId, "PASSED", "SHADOW_TEMPLATE", null);
                return fallback;
            }

            runs.finish(runId, "PASSED", "MODEL", null);
            return parsed;
        } catch (ModelUnavailableException e) {
            log.info("Trust narrator run {} fell back to template ({})", runId, e.reason());
            runs.finish(runId, "NOT_RUN", "TEMPLATE_FALLBACK", e.reason().name());
            return fallback;
        }
    }

    TrustNarrationResult parseAndValidate(String content, TrustNarrationInput input) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(content);
            if (!root.isObject()) {
                return null;
            }
            JsonNode enNode = root.get("summaryEn");
            JsonNode esNode = root.get("summaryEs");
            JsonNode attestationNode = root.get("certificateAttestation");
            JsonNode adviceNode = root.get("milestoneAdvice");
            if (enNode == null || !enNode.isTextual() ||
                esNode == null || !esNode.isTextual() ||
                attestationNode == null || !attestationNode.isTextual() ||
                adviceNode == null || !adviceNode.isTextual()) {
                return null;
            }

            String summaryEn = enNode.asText().strip();
            String summaryEs = esNode.asText().strip();
            String attestation = attestationNode.asText().strip();
            String advice = adviceNode.asText().strip();

            if (summaryEn.isBlank() || summaryEs.isBlank() || attestation.isBlank() || advice.isBlank()) {
                return null;
            }

            // Length constraints
            if (summaryEn.length() > 350 || summaryEs.length() > 380 || attestation.length() > 450 || advice.length() > 250) {
                return null;
            }

            // Content guardrails
            if (NarrationGuard.violates(summaryEn) || NarrationGuard.violates(summaryEs) ||
                NarrationGuard.violates(attestation) || NarrationGuard.violates(advice)) {
                return null;
            }

            // Grounding check for allowed dollar figures
            Set<String> allowedFigures = buildAllowedFigures(input);
            if (!allDollarFiguresAllowed(summaryEn, allowedFigures) ||
                !allDollarFiguresAllowed(summaryEs, allowedFigures) ||
                !allDollarFiguresAllowed(attestation, allowedFigures) ||
                !allDollarFiguresAllowed(advice, allowedFigures)) {
                return null;
            }

            return new TrustNarrationResult(summaryEn, summaryEs, attestation, advice, true);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static String buildUserContext(TrustNarrationInput input) {
        return """
            <computed_trust_facts>
            member_id: %s
            current_tier: %s
            next_tier: %s
            current_limit: %s
            next_limit: %s
            on_time_repayments: %d
            streak_months: %d
            total_saved: %s
            total_repaid: %s
            </computed_trust_facts>
            """.formatted(
                input.memberId(),
                input.currentTier(),
                input.nextTier(),
                formatDollar(input.currentLimitCents()),
                formatDollar(input.nextLimitCents()),
                input.onTimeRepaymentsCount(),
                input.streakMonths(),
                formatDollar(input.totalSavedCents()),
                formatDollar(input.totalRepaidCents())
            );
    }

    private static Set<String> buildAllowedFigures(TrustNarrationInput input) {
        Set<String> figures = new LinkedHashSet<>();
        figures.add(formatDollar(input.currentLimitCents()));
        figures.add(formatDollar(input.nextLimitCents()));
        figures.add(formatDollar(input.totalSavedCents()));
        figures.add(formatDollar(input.totalRepaidCents()));
        figures.add("$0");
        return figures;
    }

    private static boolean allDollarFiguresAllowed(String text, Set<String> allowed) {
        Matcher matcher = DOLLAR_AMOUNT.matcher(text);
        while (matcher.find()) {
            String found = matcher.group();
            if (!allowed.contains(found) && !allowed.contains(found.replace(".00", ""))) {
                return false;
            }
        }
        return true;
    }

    public static String formatDollar(long cents) {
        long dollars = cents / 100;
        long rem = Math.abs(cents % 100);
        if (rem == 0) {
            return String.format(Locale.US, "$%,d", dollars);
        }
        return String.format(Locale.US, "$%,d.%02d", dollars, rem);
    }

    private static String loadPrompt(String path) {
        try (InputStream in = TrustAgentRunner.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing prompt resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
