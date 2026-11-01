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
import com.paypalseed.savings.api.SavingsCoachNarrator;
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
 * Features 1 & 2 Savings & Microloan Term Coach narrator (`coach-agent`, ADR 0009 & ADR 0010).
 *
 * <p>Takes the deterministic savings plan and microloan repayment term computed by
 * {@code SuggestSavingsPlanUseCase}, requests bilingual (`en`/`es`) coaching copy via {@link ModelGateway},
 * verifies that every dollar amount in the generated copy matches the computed plan and contains no
 * banned claims, and falls back to deterministic templates on any error, grounding failure, or kill-switch
 * restriction.
 */
@Service
public class SavingsCoachAgentRunner implements SavingsCoachNarrator {

    public static final String AGENT = "coach-agent";
    public static final String SCHEMA_NAME = "savings_coach_narration";
    public static final String SCHEMA = """
        {"type":"object","additionalProperties":false,"required":["messageEn","messageEs"],\
        "properties":{"messageEn":{"type":"string"},"messageEs":{"type":"string"}}}""";

    private static final Logger log = LoggerFactory.getLogger(SavingsCoachAgentRunner.class);
    private static final int MAX_OUTPUT_TOKENS = 400;
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern DOLLAR_AMOUNT = Pattern.compile("\\$[0-9][0-9,]*(?:\\.[0-9]{1,2})?");
    private static final Pattern FORBIDDEN_TERMS = Pattern.compile(
        "(?i)\\b(fico|credit\\s+score|credit\\s+bureau|late\\s+fee|penalty|penalties|guaranteed\\s+approval|apr)\\b"
    );

    private static final String SYSTEM_PROMPT = loadPrompt("/coach/prompts/v1/suggest.md");
    public static final String PROMPT_HASH = "sha256:" + sha256Hex(SYSTEM_PROMPT + "\u0000" + SCHEMA).substring(0, 16);

    private final ModelGateway models;
    private final AgentRuns runs;
    private final KillSwitch killSwitch;
    private final ObjectMapper json;

    public SavingsCoachAgentRunner(ModelGateway models, AgentRuns runs, KillSwitch killSwitch, ObjectMapper json) {
        this.models = models;
        this.runs = runs;
        this.killSwitch = killSwitch;
        this.json = json;
    }

    @Override
    public CoachNarrationResult narrate(CoachNarrationInput input) {
        CoachNarrationResult fallback = new CoachNarrationResult(input.templateMessageEn(), input.templateMessageEs(), false);
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

            CoachNarrationResult parsed = parseAndValidate(response.content(), input);
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
            log.info("Savings coach narrator run {} fell back to template ({})", runId, e.reason());
            runs.finish(runId, "NOT_RUN", "TEMPLATE_FALLBACK", e.reason().name());
            return fallback;
        }
    }

    CoachNarrationResult parseAndValidate(String content, CoachNarrationInput input) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(content);
            if (node == null || !node.isObject()
                || !node.path("messageEn").isTextual()
                || !node.path("messageEs").isTextual()) {
                return null;
            }

            String en = node.path("messageEn").asText().strip();
            String es = node.path("messageEs").asText().strip();
            if (en.isBlank() || es.isBlank() || en.length() > 480 || es.length() > 520) {
                return null;
            }

            Set<String> allowedDollars = allowedDollars(input);
            if (!isGroundedText(en, allowedDollars) || !isGroundedText(es, allowedDollars)) {
                return null;
            }

            // Ensure both messages mention the suggested monthly saving amount and unlocked loan amount
            String expectedSaving = normalizeDollarToken(dollars(input.suggestedMonthlySavingCents()));
            String expectedLoan = normalizeDollarToken(dollars(input.unlockedLoanAmountCents()));
            if (!containsDollarToken(en, expectedSaving) || !containsDollarToken(en, expectedLoan)) {
                return null;
            }

            return new CoachNarrationResult(en, es, true);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static boolean containsDollarToken(String text, String expectedNormalizedToken) {
        Matcher m = DOLLAR_AMOUNT.matcher(text);
        while (m.find()) {
            if (normalizeDollarToken(m.group()).equals(expectedNormalizedToken)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGroundedText(String text, Set<String> allowedDollars) {
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

    private static Set<String> allowedDollars(CoachNarrationInput input) {
        long[] centsValues = {
            input.targetAmountCents(),
            input.statedMonthlyIncomeCents(),
            input.statedMonthlyExpensesCents(),
            input.disposableMonthlyIncomeCents(),
            input.suggestedMonthlySavingCents(),
            input.totalSavedAfterStreakCents(),
            input.unlockedLoanAmountCents(),
            input.maxMonthlyInstalmentCents(),
            input.suggestedMonthlyInstalmentCents()
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

    static String buildUserContext(CoachNarrationInput input) {
        StringBuilder sb = new StringBuilder();
        sb.append("<computed_plan>\n");
        sb.append("statedMonthlyIncome: ").append(dollars(input.statedMonthlyIncomeCents())).append('\n');
        sb.append("statedMonthlyExpenses: ").append(dollars(input.statedMonthlyExpensesCents())).append('\n');
        sb.append("disposableMonthlyIncome: ").append(dollars(input.disposableMonthlyIncomeCents())).append('\n');
        sb.append("suggestedMonthlySaving: ").append(dollars(input.suggestedMonthlySavingCents())).append('\n');
        sb.append("streakMonthsToUnlock: ").append(input.streakMonthsToUnlock()).append('\n');
        sb.append("totalSavedAfterStreak: ").append(dollars(input.totalSavedAfterStreakCents())).append('\n');
        sb.append("unlockedTier: ").append(input.unlockedTier()).append('\n');
        sb.append("unlockedLoanAmount: ").append(dollars(input.unlockedLoanAmountCents())).append('\n');
        sb.append("maxMonthlyInstalment: ").append(dollars(input.maxMonthlyInstalmentCents())).append('\n');
        sb.append("suggestedLoanTermMonths: ").append(input.suggestedLoanTermMonths()).append('\n');
        sb.append("suggestedMonthlyInstalment: ").append(dollars(input.suggestedMonthlyInstalmentCents())).append('\n');
        sb.append("</computed_plan>\n");
        sb.append("<goal_name>\n").append(fence(input.goalName())).append("\n</goal_name>\n");
        return sb.toString();
    }

    private static String fence(String untrusted) {
        if (untrusted == null) {
            return "";
        }
        String trimmed = untrusted.length() > 120 ? untrusted.substring(0, 120) : untrusted;
        return trimmed.replace('<', '‹').replace('>', '›');
    }

    private static String dollars(long cents) {
        if (cents % 100 == 0) {
            return String.format(Locale.US, "$%,d", cents / 100);
        }
        return String.format(Locale.US, "$%,.2f", cents / 100.0);
    }

    private static String loadPrompt(String path) {
        try (InputStream in = SavingsCoachAgentRunner.class.getResourceAsStream(path)) {
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
