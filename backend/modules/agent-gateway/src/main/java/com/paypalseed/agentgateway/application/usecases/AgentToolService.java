package com.paypalseed.agentgateway.application.usecases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paypalseed.agentgateway.api.AgentPrincipal;
import com.paypalseed.agentgateway.api.AgentRuns;
import com.paypalseed.agentgateway.api.AgentTokens;
import com.paypalseed.agentgateway.api.AgentTools;
import com.paypalseed.agentgateway.api.llm.ToolSpec;
import com.paypalseed.agentgateway.application.usecases.ToolResponses.AgingTool;
import com.paypalseed.agentgateway.application.usecases.ToolResponses.IncomeSummaryTool;
import com.paypalseed.agentgateway.application.usecases.ToolResponses.InstalmentRow;
import com.paypalseed.agentgateway.application.usecases.ToolResponses.LoanSummaryTool;
import com.paypalseed.agentgateway.application.usecases.ToolResponses.NextInstalment;
import com.paypalseed.agentgateway.application.usecases.ToolResponses.OfferedOptionsTool;
import com.paypalseed.agentgateway.application.usecases.ToolResponses.OptionPreview;
import com.paypalseed.agentgateway.application.usecases.ToolResponses.PriorCase;
import com.paypalseed.agentgateway.application.usecases.ToolResponses.PriorCasesTool;
import com.paypalseed.agentgateway.application.usecases.ToolResponses.ScheduleTool;
import com.paypalseed.agentgateway.application.usecases.ToolResponses.SignalRow;
import com.paypalseed.agentgateway.application.usecases.ToolResponses.SignalsTool;
import com.paypalseed.agentgateway.domain.model.AgentErrors;
import com.paypalseed.agentgateway.domain.model.OptionNames;
import com.paypalseed.agentgateway.domain.model.ToolCatalogue;
import com.paypalseed.engines.rules.hardship.HardshipPolicy;
import com.paypalseed.hardship.api.HardshipFacade;
import com.paypalseed.hardship.api.dto.HardshipDtos.OptionView;
import com.paypalseed.hardship.api.dto.HardshipDtos.SignalsView;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.api.dto.LendingDtos.RiskFacts;
import com.paypalseed.lending.api.dto.LendingDtos.ScheduleView;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.underwriting.api.UnderwritingFacade;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Serves the read tools. Every call checks the scope, the kill switch, the run's loan and the per-run cap,
 * and is recorded on the run trace by tool name, latency and an argument hash. Answers carry numbers,
 * enums and dates only: nothing a member typed, no names, contact details or PayPal ids.
 */
@Service
public class AgentToolService implements AgentTools {

    private static final int GRACE_DAYS = HardshipPolicy.v1().scoring().graceDays();

    private final AgentTokens tokens;
    private final AgentRuns runs;
    private final AgentGuard guard;
    private final LendingFacade lending;
    private final HardshipFacade hardship;
    private final UnderwritingFacade underwriting;
    private final BusinessClock clock;
    private final ObjectMapper json;

    public AgentToolService(AgentTokens tokens, AgentRuns runs, AgentGuard guard, LendingFacade lending,
                            HardshipFacade hardship, UnderwritingFacade underwriting, BusinessClock clock, ObjectMapper json) {
        this.tokens = tokens;
        this.runs = runs;
        this.guard = guard;
        this.lending = lending;
        this.hardship = hardship;
        this.underwriting = underwriting;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public List<ToolSpec> catalogue() {
        return ToolCatalogue.specs();
    }

    @Override
    public ToolResult call(String token, String toolName, String argumentsJson) {
        Optional<AgentPrincipal> principal = tokens.verify(token);
        if (principal.isEmpty()) {
            return refused(toolName, CommonErrors.UNAUTHENTICATED.code(), "The run token is unknown, expired or revoked");
        }
        JsonNode args;
        try {
            args = json.readTree(argumentsJson == null ? "{}" : argumentsJson);
        } catch (JsonProcessingException e) {
            return refused(toolName, AgentErrors.INVALID_ARGUMENTS.code(), "The arguments are not valid JSON");
        }
        if (args == null || !args.isObject()) {
            return refused(toolName, AgentErrors.INVALID_ARGUMENTS.code(), "The arguments must be a JSON object");
        }
        try {
            return new ToolResult(toolName, true, json.writeValueAsString(execute(principal.get(), toolName, (ObjectNode) args)), null);
        } catch (DomainException e) {
            return refused(toolName, e.errorCode().code(), e.getMessage());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tool result could not be serialised", e);
        }
    }

    /** Shared by the HTTP endpoints and in-process callers. Refusals are {@link DomainException}s. */
    public Object execute(AgentPrincipal principal, String toolName, ObjectNode args) {
        ToolCatalogue.Tool tool = ToolCatalogue.find(toolName)
                .orElseThrow(() -> new DomainException(CommonErrors.NOT_FOUND, "No such tool"));
        principal.requireScope(tool.scope());
        guard.requireAgentsOn(principal);
        UUID loanId = loanId(args);
        principal.requireLoan(loanId);
        if (runs.toolCallCount(principal.runId()) >= MAX_CALLS_PER_RUN) {
            throw new DomainException(AgentErrors.TOOL_CALL_LIMIT,
                    "This run has used all " + MAX_CALLS_PER_RUN + " of its tool calls; write the answer now");
        }
        long started = System.nanoTime();
        try {
            return switch (toolName) {
                case GET_LOAN_SUMMARY -> loanSummary(loanId);
                case GET_REPAYMENT_SCHEDULE -> schedule(loanId);
                case GET_LOAN_AGING -> aging(loanId);
                case GET_HARDSHIP_SIGNALS -> signals(principal);
                case GET_INCOME_SUMMARY -> income(loanId);
                case LIST_OFFERED_OPTIONS -> new OfferedOptionsTool(loanId, offered(principal));
                case PREVIEW_OPTION -> preview(principal, args);
                case LIST_PRIOR_CASES -> priorCases(loanId);
                default -> throw new DomainException(CommonErrors.NOT_FOUND, "No such tool");
            };
        } finally {
            runs.recordToolCall(principal.runId(), toolName, (System.nanoTime() - started) / 1_000_000, sha256Hex(args.toString()));
        }
    }

    private LoanSummaryTool loanSummary(UUID loanId) {
        LoanSummary loan = lending.loan(loanId).orElseThrow(() -> new DomainException(CommonErrors.NOT_FOUND, "No such loan"));
        ScheduleView schedule = lending.currentSchedule(loanId);
        int paid = (int) schedule.instalments().stream().filter(i -> i.remainingCents() == 0).count();
        return new LoanSummaryTool(loanId, memberRef(loan.memberId()), loan.principalCents(), loan.outstandingCents(),
                loan.status(), loan.bucket(), loan.dpd(), schedule.instalments().size(), paid, loan.collectionMode(),
                clock.today());
    }

    private ScheduleTool schedule(UUID loanId) {
        ScheduleView s = lending.currentSchedule(loanId);
        List<InstalmentRow> rows = s.instalments().stream()
                .map(i -> new InstalmentRow(i.seq(), i.dueDate(), i.amountCents(), i.paidCents(), i.remainingCents()))
                .toList();
        return new ScheduleTool(loanId, s.version(), s.reason(), clock.today(), rows);
    }

    private AgingTool aging(UUID loanId) {
        LocalDate today = clock.today();
        RiskFacts facts = lending.riskFacts(loanId, today)
                .orElseThrow(() -> new DomainException(CommonErrors.NOT_FOUND, "No such loan"));
        NextInstalment next = facts.targetSeq() == null ? null : new NextInstalment(facts.targetSeq(), facts.targetDueDate(),
                facts.targetAmountCents(), facts.targetRemainingCents(), ChronoUnit.DAYS.between(today, facts.targetDueDate()));
        LocalDate graceEndsOn = next != null && next.daysUntilDue() < 0 ? facts.targetDueDate().plusDays(GRACE_DAYS) : null;
        int missed = (int) lending.currentSchedule(loanId).instalments().stream()
                .filter(i -> i.remainingCents() > 0 && i.dueDate().plusDays(GRACE_DAYS).isBefore(today))
                .count();
        return new AgingTool(loanId, facts.bucket(), facts.dpd(), next, graceEndsOn, missed, today);
    }

    private SignalsTool signals(AgentPrincipal principal) {
        SignalsView v = hardship.signals(principal.caseId());
        List<SignalRow> rows = v.signals().stream().map(s -> new SignalRow(s.code(), s.points(), s.evidence())).toList();
        return new SignalsTool(principal.loanId(), v.policyVersion(), v.computedLevel(), v.points(), rows, v.suppressed());
    }

    private IncomeSummaryTool income(UUID loanId) {
        LoanSummary loan = lending.loan(loanId).orElseThrow(() -> new DomainException(CommonErrors.NOT_FOUND, "No such loan"));
        IncomeSummary summary = underwriting.incomeSummary(loan.memberId(), clock.today())
                .orElseThrow(() -> new DomainException(AgentErrors.NO_CONSENT, "The member has not shared income data"));
        List<Long> weeks = summary.weeklyInflowCents().stream().map(AgentToolService::wholeDollars).toList();
        long median = weeks.stream().sorted().toList().get(weeks.size() / 2);
        long last4 = weeks.subList(weeks.size() - 4, weeks.size()).stream().mapToLong(Long::longValue).sum() / 4;
        String payday = summary.usualPaydayDow() == null ? null : DayOfWeek.of(summary.usualPaydayDow()).name();
        return new IncomeSummaryTool(loanId, weeks.size() * 7, median, wholeDollars(last4), payday, weeks);
    }

    private List<OptionPreview> offered(AgentPrincipal principal) {
        return hardship.options(principal.caseId()).stream().map(AgentToolService::preview).toList();
    }

    private OptionPreview preview(AgentPrincipal principal, ObjectNode args) {
        JsonNode option = args.get("option");
        if (option == null || !option.isTextual()) {
            throw new DomainException(AgentErrors.INVALID_ARGUMENTS, "option is required");
        }
        String label = OptionNames.labelFor(option.asText())
                .orElseThrow(() -> new DomainException(AgentErrors.INVALID_ARGUMENTS, "Unknown option " + option.asText()));
        return hardship.options(principal.caseId()).stream()
                .filter(o -> o.label().equals(label))
                .findFirst()
                .map(AgentToolService::preview)
                .orElseThrow(() -> new DomainException(AgentErrors.OPTION_NOT_OFFERED, option.asText() + " is not on this offer"));
    }

    private PriorCasesTool priorCases(UUID loanId) {
        List<PriorCase> cases = hardship.priorCases(loanId).stream()
                .map(c -> new PriorCase(c.caseId(), c.openedOn(), c.closedOn(),
                        c.chosenOption() == null ? null : OptionNames.forLabel(c.chosenOption()), c.outcome()))
                .toList();
        return new PriorCasesTool(loanId, cases);
    }

    private static OptionPreview preview(OptionView o) {
        Map<String, Object> summary = new LinkedHashMap<>();
        o.summary().forEach((key, value) ->
                summary.put(key, key.endsWith("Date") && value != null ? LocalDate.ofEpochDay(value).toString() : value));
        return new OptionPreview(OptionNames.forLabel(o.label()), o.label(), o.primitive(), OptionNames.previewHash(o.snapshotHash()),
                true, OptionNames.tier(o.tier()), o.args(), summary, o.sustainable(), o.warnings(), o.recommended());
    }

    private static UUID loanId(ObjectNode args) {
        JsonNode id = args.get("loanId");
        if (id == null || !id.isTextual()) {
            throw new DomainException(AgentErrors.INVALID_ARGUMENTS, "loanId is required");
        }
        try {
            return UUID.fromString(id.asText());
        } catch (IllegalArgumentException e) {
            throw new DomainException(CommonErrors.NOT_FOUND, "No such loan");
        }
    }

    private static long wholeDollars(long cents) {
        return cents / 100 * 100;
    }

    /** A stable pseudonym: officers and agents can tell members apart without learning who they are. */
    static String memberRef(UUID memberId) {
        byte[] digest = sha256(("seed-member-ref:" + memberId).getBytes(StandardCharsets.UTF_8));
        return "m_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 10);
    }

    private static String sha256Hex(String text) {
        return HexFormat.of().formatHex(sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ToolResult refused(String tool, String code, String detail) {
        ObjectNode body = json.createObjectNode().put("error", code).put("detail", detail);
        return new ToolResult(tool, false, body.toString(), code);
    }
}
