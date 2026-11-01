package com.paypalseed.guardian.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paypalseed.engines.rules.hardship.RiskLevel;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the {@code HardshipProposal} of {@code agent-tools.yaml}. The options, their preview hashes and the
 * recommended flag are copied from the code's own tool answer, never from model text; the model contributes
 * only the narrative and the figures it cites, which the validator checks.
 */
final class ProposalAssembler {

    record Assessment(RiskLevel computedLevel, RiskLevel proposedLevel, boolean escalated, List<String> citedSignals) {
    }

    private static final Set<String> NUMERIC_UNITS = Set.of("USD_CENTS", "PERCENT", "DAYS", "COUNT");
    private static final List<String> OPTION_ARGS = List.of("seq", "days", "extraMonths", "amountCents");

    private ProposalAssembler() {
    }

    static ObjectNode assemble(ObjectMapper json, UUID runId, UUID caseId, UUID loanId, String agentVersion, String promptHash,
                               Assessment assessment, JsonNode offeredOptions, JsonNode narrative) {
        ObjectNode proposal = json.createObjectNode()
                .put("runId", runId.toString())
                .put("caseId", caseId.toString())
                .put("loanId", loanId.toString())
                .put("agentVersion", agentVersion)
                .put("promptHash", promptHash);

        ObjectNode a = proposal.putObject("assessment")
                .put("computedLevel", assessment.computedLevel().name())
                .put("proposedLevel", assessment.proposedLevel().name())
                .put("escalated", assessment.escalated());
        ArrayNode cited = a.putArray("citedSignals");
        assessment.citedSignals().forEach(cited::add);

        ArrayNode options = proposal.putArray("options");
        for (JsonNode o : offeredOptions.path("options")) {
            ObjectNode option = options.addObject()
                    .put("option", o.path("option").asText())
                    .put("label", o.path("label").asText());
            JsonNode args = o.path("args");
            for (String key : OPTION_ARGS) {
                if (args.path(key).isIntegralNumber()) {
                    option.put(key, args.path(key).asLong());
                }
            }
            option.put("previewHash", o.path("previewHash").asText(null));
            option.put("recommended", o.path("recommended").asBoolean(false));
        }

        ArrayNode figures = proposal.putArray("figures");
        for (JsonNode f : narrative.path("figures")) {
            ObjectNode figure = figures.addObject();
            String unit = f.path("unit").asText();
            putValue(figure, f.path("value").asText(), NUMERIC_UNITS.contains(unit));
            figure.put("unit", unit).put("kind", f.path("kind").asText());
            figure.putObject("source").put("tool", f.path("tool").asText()).put("field", f.path("field").asText());
        }

        ObjectNode n = proposal.putObject("narrative").put("officerBrief", narrative.path("officerBrief").asText());
        n.putObject("memberMessage")
                .put("en", narrative.path("memberMessage").path("en").asText())
                .put("es", narrative.path("memberMessage").path("es").asText());
        return proposal;
    }

    /** Numbers stay numbers when they parse; anything else is passed on as text for the validator to judge. */
    private static void putValue(ObjectNode figure, String value, boolean numeric) {
        if (numeric) {
            try {
                figure.put("value", Long.parseLong(value.replace(",", "").trim()));
                return;
            } catch (NumberFormatException e) {
                // fall through: the validator rejects an ungrounded value
            }
        }
        figure.put("value", value);
    }
}
