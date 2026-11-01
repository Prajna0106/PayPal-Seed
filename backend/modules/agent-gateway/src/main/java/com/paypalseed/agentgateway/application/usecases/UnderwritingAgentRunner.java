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
import com.paypalseed.underwriting.api.FairCheckNarrator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
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
 * Feature 3 Fair Loan Check narrator (`underwriting-agent`, ADR 0009 & ADR 0010).
 *
 * <p>Reads only the deterministic figures already computed by {@code AssessmentEngine}, asks the model
 * through {@link ModelGateway} for plain-language reasons and 1–2 officer follow-up questions in strict JSON,
 * verifies that every dollar figure in the output is grounded in the input figures and free of banned
 * credit/fee claims, and falls back to the deterministic template reasons and questions on any error,
 * grounding failure, or when the kill switch is in {@code SHADOW} or {@code TEMPLATES_ONLY}.
 */
@Service
public class UnderwritingAgentRunner implements FairCheckNarrator {

    public static final String AGENT = "underwriting-agent";
    public static final String SCHEMA_NAME = "fair_check_narration";
    public static final String SCHEMA = """
        {"type":"object","additionalProperties":false,"required":["plainLanguageReasons","officerQuestions"],\
        "properties":{"plainLanguageReasons":{"type":"array","items":{"type":"string"}},\
        "officerQuestions":{"type":"array","items":{"type":"string"}}}}""";

    private static final Logger log = LoggerFactory.getLogger(UnderwritingAgentRunner.class);
    private static final int MAX_OUTPUT_TOKENS = 600;
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(12);
    private static final Pattern DOLLAR_AMOUNT = Pattern.compile("\\$[0-9][0-9,]*(?:\\.[0-9]{1,2})?");
    private static final Pattern FORBIDDEN_TERMS = Pattern.compile(
        "(?i)\\b(fico|credit\\s+score|credit\\s+bureau|late\\s+fee|penalty|penalties|guaranteed\\s+approval|apr)\\b"
    );

    private static final String SYSTEM_PROMPT = loadPrompt("/underwriting/prompts/v1/explain.md");
    public static final String PROMPT_HASH = "sha256:" + sha256Hex(SYSTEM_PROMPT + "\u0000" + SCHEMA).substring(0, 16);

    private final ModelGateway models;
    private final AgentRuns runs;
    private final KillSwitch killSwitch;
    private final ObjectMapper json;

    public UnderwritingAgentRunner(ModelGateway models, AgentRuns runs, KillSwitch killSwitch, ObjectMapper json) {
        this.models = models;
        this.runs = runs;
        this.killSwitch = killSwitch;
        this.json = json;
    }

    @Override
    public NarrationResult narrate(NarrationInput input) {
        NarrationResult fallback = new NarrationResult(input.fallbackReasons(), input.fallbackOfficerQuestions());
        Mode mode = killSwitch.mode(AGENT);
        UUID runId = runs.start(AGENT, input.assessmentId(), input.applicationId(), PROMPT_HASH, "NARRATIVE");
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

            NarrationResult parsed = parseAndValidate(response.content(), input);
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
            log.info("Underwriting narrator run {} fell back to template ({})", runId, e.reason());
            runs.finish(runId, "NOT_RUN", "TEMPLATE_FALLBACK", e.reason().name());
            return fallback;
        }
    }

    NarrationResult parseAndValidate(String content, NarrationInput input) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(content);
            if (node == null || !node.isObject()
                || !node.path("plainLanguageReasons").isArray()
                || !node.path("officerQuestions").isArray()) {
                return null;
            }

            List<String> reasons = new ArrayList<>();
            for (JsonNode item : node.path("plainLanguageReasons")) {
                if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 400) {
                    return null;
                }
                reasons.add(item.asText().strip());
            }

            List<String> questions = new ArrayList<>();
            for (JsonNode item : node.path("officerQuestions")) {
                if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 400) {
                    return null;
                }
                questions.add(item.asText().strip());
            }

            if (reasons.size() < 2 || reasons.size() > 6 || questions.isEmpty() || questions.size() > 3) {
                return null;
            }

            Set<String> allowedDollarStrings = allowedDollars(input);
            for (String line : reasons) {
                if (!isGroundedLine(line, allowedDollarStrings)) {
                    return null;
                }
            }
            for (String q : questions) {
                if (!isGroundedLine(q, allowedDollarStrings)) {
                    return null;
                }
            }

            return new NarrationResult(reasons, questions);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static boolean isGroundedLine(String text, Set<String> allowedDollars) {
        if (FORBIDDEN_TERMS.matcher(text).find()) {
            return false;
        }
        Matcher m = DOLLAR_AMOUNT.matcher(text);
        while (m.find()) {
            String token = normalizeDollarToken(m.group());
            if (!allowedDollars.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> allowedDollars(NarrationInput input) {
        long[] centsValues = {
            input.averageMonthlyIncomeCents(),
            input.lowestMonthlyIncomeCents(),
            input.provenMonthlySavingCents(),
            input.instalmentCapCents(),
            input.requestedAmountCents(),
            input.suggestedLoanCents(),
            input.suggestedMonthlyInstalmentCents(),
            0L
        };
        Set<String> allowed = new LinkedHashSet<>();
        for (long c : centsValues) {
            allowed.add(normalizeDollarToken(dollars(c)));
            allowed.add(String.format(Locale.US, "$%.2f", c / 100.0));
        }
        return allowed;
    }

    private static String normalizeDollarToken(String token) {
        String clean = token.replace(",", "");
        if (clean.endsWith(".00")) {
            return clean.substring(0, clean.length() - 3);
        }
        return clean;
    }

    static String buildUserContext(NarrationInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("<computed_facts>\n");
        sb.append("recommended: ").append(input.recommended()).append('\n');
        sb.append("unlockedTier: ").append(input.unlockedTier()).append('\n');
        sb.append("usedFallbackStatedIncome: ").append(input.usedFallbackStatedIncome()).append('\n');
        sb.append("averageMonthlyIncome: ").append(dollars(input.averageMonthlyIncomeCents())).append('\n');
        sb.append("lowestMonthlyIncome: ").append(dollars(input.lowestMonthlyIncomeCents())).append('\n');
        sb.append("lowestIncomeMonth: ").append(input.lowestIncomeMonth() != null ? input.lowestIncomeMonth() : "N/A").append('\n');
        sb.append("coefficientOfVariationPct: ").append(String.format(Locale.US, "%.0f%%", input.coefficientOfVariation() * 100.0)).append('\n');
        sb.append("highIncomeVolatility: ").append(input.highIncomeVolatility()).append('\n');
        sb.append("provenMonthlySaving: ").append(dollars(input.provenMonthlySavingCents())).append('\n');
        sb.append("monthsSaved: ").append(input.monthsSaved()).append(" of ").append(input.targetMonthsWindow()).append('\n');
        sb.append("safeMonthlyInstalmentCap: ").append(dollars(input.instalmentCapCents())).append('\n');
        sb.append("requestedLoanAmount: ").append(dollars(input.requestedAmountCents())).append('\n');
        sb.append("requestedTermMonths: ").append(input.requestedTermMonths()).append('\n');
        sb.append("suggestedLoanAmount: ").append(dollars(input.suggestedLoanCents())).append('\n');
        sb.append("suggestedTermMonths: ").append(input.suggestedTermMonths()).append('\n');
        sb.append("suggestedMonthlyInstalment: ").append(dollars(input.suggestedMonthlyInstalmentCents())).append('\n');
        sb.append("purpose: ").append(fence(input.purpose())).append('\n');
        sb.append("</computed_facts>\n");
        if (input.memberDisputeNote() != null && !input.memberDisputeNote().isBlank()) {
            sb.append("<member_dispute_note>\n").append(fence(input.memberDisputeNote())).append("\n</member_dispute_note>\n");
        }
        return sb.toString();
    }

    private static String fence(String untrusted) {
        if (untrusted == null) {
            return "";
        }
        String trimmed = untrusted.length() > 300 ? untrusted.substring(0, 300) : untrusted;
        return trimmed.replace('<', '‹').replace('>', '›');
    }

    private static String dollars(long cents) {
        if (cents % 100 == 0) {
            return String.format(Locale.US, "$%,d", cents / 100);
        }
        return String.format(Locale.US, "$%,.2f", cents / 100.0);
    }

    private static String loadPrompt(String path) {
        try (InputStream in = UnderwritingAgentRunner.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing prompt " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
