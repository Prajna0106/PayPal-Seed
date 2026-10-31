package com.paypalseed.hardship.application.usecases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.engines.rules.hardship.GroundingChecker;
import com.paypalseed.engines.rules.hardship.GroundingChecker.Allowed;
import com.paypalseed.engines.rules.hardship.GroundingChecker.Audience;
import com.paypalseed.engines.rules.hardship.HardshipCaps;
import com.paypalseed.engines.rules.hardship.HardshipPolicy;
import com.paypalseed.engines.rules.hardship.OptionKind;
import com.paypalseed.engines.rules.hardship.RiskLevel;
import com.paypalseed.engines.rules.hardship.ScoreResult;
import com.paypalseed.engines.rules.hardship.SecondOpinion;
import com.paypalseed.engines.rules.hardship.Signal;
import com.paypalseed.governance.api.ProposalValidator;
import com.paypalseed.governance.api.dto.GovernanceDtos.Check;
import com.paypalseed.governance.api.dto.GovernanceDtos.ProposalEnvelope;
import com.paypalseed.governance.api.dto.GovernanceDtos.Verdict;
import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.OfferModel.OfferRow;
import com.paypalseed.hardship.domain.model.OfferModel.OptionRow;
import com.paypalseed.hardship.infrastructure.persistence.CaseRepository;
import com.paypalseed.hardship.infrastructure.persistence.OfferRepository;
import com.paypalseed.hardship.infrastructure.persistence.ScorecardRepository;
import com.paypalseed.hardship.infrastructure.persistence.ScorecardRepository.StoredSignal;
import com.paypalseed.platform.kernel.BusinessClock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Judges the Guardian's {@code HARDSHIP_OUTREACH} proposals (04-agent-architecture §5.2, checks V01 to V12). Pure
 * code over the case's own stored data: it never calls a model, and a proposal that fails any check is never shown
 * to a member or an officer. Check details name codes and counts only, never the proposed text.
 */
@Component
public class OutreachProposalValidator implements ProposalValidator {

    public static final String KIND = "HARDSHIP_OUTREACH";

    private static final Set<String> TOP_LEVEL = Set.of("runId", "caseId", "loanId", "agentVersion", "promptHash", "assessment",
            "options", "figures", "narrative");
    private static final Pattern PREVIEW_HASH = Pattern.compile("^sha256:[0-9a-f]{16}$");
    private static final List<String> FORBIDDEN = List.of("fee", "interest", "penalty", "guaranteed", "forgiven", "legal",
            "collections", "urgent", "final notice", "credit score", "cargo", "interés", "multa", "garantizado", "cobranza",
            "urgente", "puntaje de crédito", "approved", "waived", "aprobado", "aprobada");
    /** Links, email addresses and phone numbers: a model never hands a member somewhere to go or someone to call. */
    private static final Pattern CONTACT = Pattern.compile(
            "(?i)\\bhttps?://|\\bwww\\.|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|\\+\\d{8,15}\\b"
                    + "|\\(?\\b\\d{3}\\)?[ .-]\\d{3}[ .-]\\d{4}\\b");

    private final CaseRepository cases;
    private final OfferRepository offers;
    private final ScorecardRepository scorecards;
    private final HardshipSettings settings;
    private final BusinessClock clock;
    private final ObjectMapper json;

    public OutreachProposalValidator(CaseRepository cases, OfferRepository offers, ScorecardRepository scorecards,
                                     HardshipSettings settings, BusinessClock clock, ObjectMapper json) {
        this.cases = cases;
        this.offers = offers;
        this.scorecards = scorecards;
        this.settings = settings;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Verdict validate(ProposalEnvelope proposal) {
        List<Check> checks = new ArrayList<>();
        JsonNode p;
        try {
            p = json.readTree(proposal.payloadJson());
        } catch (JsonProcessingException e) {
            return Verdict.of(List.of(new Check("V01_SCHEMA", false, "not JSON")));
        }
        String schema = schema(p);
        checks.add(new Check("V01_SCHEMA", schema == null, schema));
        if (schema != null) {
            return Verdict.of(checks);
        }
        Optional<CaseRow> found = cases.find(proposal.subjectId());
        boolean bound = found.isPresent() && found.get().id().toString().equals(p.path("caseId").asText())
                && found.get().loanId().toString().equals(p.path("loanId").asText());
        checks.add(new Check("V02_TOKEN_BINDING", bound, bound ? null : "case or loan does not match the subject"));
        if (!bound) {
            return Verdict.of(checks);
        }
        CaseRow c = found.get();
        boolean accepts = !c.status().terminal();
        checks.add(new Check("V12_CASE_STATE", accepts, accepts ? null : "case is " + c.status()));
        Optional<OfferRow> offer = offers.active(c.id()).or(() -> offers.latest(c.id()));
        List<OptionRow> options = offer.map(o -> offers.options(o.id())).orElse(List.of());
        List<StoredSignal> signals = scorecards.signals(c.runId());
        HardshipPolicy policy = settings.policy();

        checks.add(level(p.path("assessment"), c, signals, policy));
        checks.addAll(optionChecks(p.path("options"), options));
        checks.add(grounding(p, c, options, signals, policy));
        checks.add(forbidden(p));
        checks.add(policy(c, options, policy));
        checks.add(outreachLimit(c, policy));
        checks.add(language(p));
        return Verdict.of(checks);
    }

    /** V01: required fields present, no unknown top-level fields, sizes within the contract. */
    private static String schema(JsonNode p) {
        if (!p.isObject()) {
            return "not an object";
        }
        for (Iterator<String> it = p.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!TOP_LEVEL.contains(f)) {
                return "unknown field";
            }
        }
        for (String f : TOP_LEVEL) {
            if (p.path(f).isMissingNode() || p.path(f).isNull()) {
                return "missing " + f;
            }
        }
        if (!PREVIEW_HASH.matcher(p.path("promptHash").asText()).matches()) {
            return "promptHash format";
        }
        JsonNode options = p.path("options");
        if (!options.isArray() || options.isEmpty() || options.size() > 4) {
            return "options must hold 1 to 4 entries";
        }
        if (!p.path("figures").isArray() || p.path("figures").size() > 40) {
            return "figures must hold at most 40 entries";
        }
        JsonNode n = p.path("narrative");
        if (!n.path("officerBrief").isTextual() || !n.path("memberMessage").path("en").isTextual()
                || !n.path("memberMessage").path("es").isTextual()) {
            return "narrative texts missing";
        }
        JsonNode a = p.path("assessment");
        if (!a.path("computedLevel").isTextual() || !a.path("proposedLevel").isTextual() || !a.path("citedSignals").isArray()) {
            return "assessment incomplete";
        }
        return null;
    }

    /** V03: the computed level is the case's own; any change is one step up, citing signals that really fired. */
    private Check level(JsonNode a, CaseRow c, List<StoredSignal> signals, HardshipPolicy policy) {
        RiskLevel computed;
        RiskLevel proposed;
        try {
            computed = RiskLevel.valueOf(a.path("computedLevel").asText());
            proposed = RiskLevel.valueOf(a.path("proposedLevel").asText());
        } catch (IllegalArgumentException e) {
            return new Check("V03_LEVEL_NOT_ESCALATE_ONLY", false, "unknown level");
        }
        if (computed != RiskLevel.valueOf(c.riskLevel())) {
            return new Check("V03_LEVEL_NOT_ESCALATE_ONLY", false, "computedLevel differs from the case");
        }
        if (proposed == computed) {
            return new Check("V03_LEVEL_NOT_ESCALATE_ONLY", true, null);
        }
        List<String> cited = new ArrayList<>();
        a.path("citedSignals").forEach(s -> cited.add(s.asText()));
        List<Signal> fired = signals.stream().filter(s -> "SCORECARD".equals(s.source()))
                .map(s -> new Signal(s.code(), s.points(), s.evidence())).toList();
        ScoreResult result = new ScoreResult(c.points(), computed, fired);
        SecondOpinion.Outcome outcome = SecondOpinion.evaluate(result, new SecondOpinion.Opinion(proposed, cited, "AGENT"),
                true, policy);
        boolean ok = outcome instanceof SecondOpinion.Accepted;
        return new Check("V03_LEVEL_NOT_ESCALATE_ONLY", ok, ok ? null : outcome.getClass().getSimpleName());
    }

    /** V04 and V05: the same option set the engine built, with its hashes, and the code's recommendation. */
    private static List<Check> optionChecks(JsonNode proposed, List<OptionRow> offered) {
        Map<String, OptionRow> byLabel = new HashMap<>();
        offered.forEach(o -> byLabel.put(o.label(), o));
        Set<String> seen = new HashSet<>();
        boolean setOk = true;
        boolean recommendationOk = true;
        int recommendedCount = 0;
        for (JsonNode o : proposed) {
            String label = AllowedFigures.optionNames().get(o.path("option").asText());
            OptionRow row = label == null ? null : byLabel.get(label);
            if (row == null || !seen.add(label) || !hashMatches(o.path("previewHash").asText(), row.snapshotHash())) {
                setOk = false;
                continue;
            }
            boolean rec = o.path("recommended").asBoolean(false);
            recommendedCount += rec ? 1 : 0;
            recommendationOk &= rec == row.recommended();
        }
        setOk &= seen.equals(byLabel.keySet());
        boolean anyOffered = offered.stream().anyMatch(OptionRow::recommended);
        recommendationOk &= recommendedCount == (anyOffered ? 1 : 0);
        return List.of(new Check("V04_OPTION_SET_OR_PREVIEW_MISMATCH", setOk, setOk ? null : "option set or hash differs"),
                new Check("V05_RECOMMENDATION_NOT_FROM_CODE", recommendationOk, recommendationOk ? null : "recommendation differs"));
    }

    /** Agents see a short form of the snapshot hash ({@code sha256:} plus 16 hex digits); either form is accepted. */
    static boolean hashMatches(String given, String snapshotHash) {
        return given.equals(snapshotHash) || given.equals("sha256:" + snapshotHash.substring(0, 16));
    }

    /** V06: every figure listed and every number in every text is one the code produced for this case. */
    private Check grounding(JsonNode p, CaseRow c, List<OptionRow> options, List<StoredSignal> signals, HardshipPolicy policy) {
        Allowed member = AllowedFigures.build(c, options, signals, Audience.MEMBER, policy);
        Allowed officer = AllowedFigures.build(c, options, signals, Audience.OFFICER, policy);
        for (JsonNode f : p.path("figures")) {
            Object value = f.path("value").isNumber() ? f.path("value").numberValue() : f.path("value").asText();
            if (!AllowedFigures.contains(officer, f.path("unit").asText(), value)) {
                return new Check("V06_UNGROUNDED_FIGURE", false, "figure of kind " + safe(f.path("kind").asText()));
            }
        }
        JsonNode n = p.path("narrative");
        for (String locale : List.of("en", "es")) {
            GroundingChecker.Result r = GroundingChecker.check(n.path("memberMessage").path(locale).asText(), member, locale,
                    Audience.MEMBER, clock.today());
            if (!r.passed()) {
                return new Check("V06_UNGROUNDED_FIGURE", false, "member " + locale + ": " + r.failureKind());
            }
        }
        GroundingChecker.Result brief = GroundingChecker.check(n.path("officerBrief").asText(), officer, "en", Audience.OFFICER,
                clock.today());
        return new Check("V06_UNGROUNDED_FIGURE", brief.passed(), brief.passed() ? null : "brief: " + brief.failureKind());
    }

    /** V07: no fees, pressure, legal threats or credit-score claims in any text. */
    private static Check forbidden(JsonNode p) {
        JsonNode n = p.path("narrative");
        String all = (n.path("officerBrief").asText() + " " + n.path("memberMessage").path("en").asText() + " "
                + n.path("memberMessage").path("es").asText()).toLowerCase(Locale.ROOT);
        if (CONTACT.matcher(all).find()) {
            return new Check("V07_FORBIDDEN_CONTENT", false, "link or contact detail");
        }
        for (String w : FORBIDDEN) {
            if (Pattern.compile("\\b" + Pattern.quote(w) + "\\b", Pattern.UNICODE_CASE).matcher(all).find()) {
                return new Check("V07_FORBIDDEN_CONTENT", false, "forbidden term");
            }
        }
        return new Check("V07_FORBIDDEN_CONTENT", true, null);
    }

    /** V08: every offered option is still within the frequency caps. */
    private Check policy(CaseRow c, List<OptionRow> options, HardshipPolicy policy) {
        CaseRepository.AppliedCounts counts = cases.appliedCounts(c.loanId(),
                clock.today().minusDays(policy.options().capWindowDays()));
        HardshipCaps.History history = new HardshipCaps.History(counts.aInWindow(), counts.bInWindow(), counts.bTotal(),
                counts.cTotal());
        for (OptionRow o : options) {
            if (HardshipCaps.violation(OptionKind.fromLabel(o.label()), history, policy).isPresent()) {
                return new Check("V08_POLICY_INELIGIBLE", false, "option " + o.label());
            }
        }
        return new Check("V08_POLICY_INELIGIBLE", true, null);
    }

    /** V09: a proactive message that has not gone out yet must still fit the member's outreach limits. */
    private Check outreachLimit(CaseRow c, HardshipPolicy policy) {
        if (c.origin() != CaseRow.Origin.PROACTIVE_SCAN || c.outreachSentAt() != null) {
            return new Check("V09_OUTREACH_LIMIT", true, null);
        }
        int recent = cases.outreachesSince(c.memberId(), clock.now().minus(30, ChronoUnit.DAYS));
        boolean ok = recent < policy.outreach().per30Days();
        return new Check("V09_OUTREACH_LIMIT", ok, ok ? null : "limit reached");
    }

    /** V10 and V11 are part of the grounding check (language and length); this confirms both locales are present. */
    private static Check language(JsonNode p) {
        JsonNode m = p.path("narrative").path("memberMessage");
        boolean ok = !m.path("en").asText().isBlank() && !m.path("es").asText().isBlank();
        return new Check("V10_LANGUAGE", ok, ok ? null : "a locale is empty");
    }

    private static String safe(String kind) {
        return kind.replaceAll("[^A-Z_]", "").substring(0, Math.min(32, kind.replaceAll("[^A-Z_]", "").length()));
    }
}
