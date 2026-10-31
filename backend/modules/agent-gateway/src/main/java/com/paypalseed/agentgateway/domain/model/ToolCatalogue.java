package com.paypalseed.agentgateway.domain.model;

import com.paypalseed.agentgateway.api.AgentTokens;
import com.paypalseed.agentgateway.api.AgentTools;
import com.paypalseed.agentgateway.api.llm.ToolSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The read tools, each with the scope it needs and the parameter schema a model sees. Every tool takes the
 * run's {@code loanId}; any other id answers 404. None returns text a member typed.
 */
public final class ToolCatalogue {

    public record Tool(String name, String scope, String description, String parametersSchema) {

        public ToolSpec spec() {
            return new ToolSpec(name, description, parametersSchema);
        }
    }

    private static final String LOAN_ONLY = """
            {"type":"object","additionalProperties":false,"required":["loanId"],\
            "properties":{"loanId":{"type":"string","format":"uuid","description":"The loan of this run."}}}""";

    private static final String PREVIEW = """
            {"type":"object","additionalProperties":false,"required":["loanId","option"],\
            "properties":{"loanId":{"type":"string","format":"uuid"},\
            "option":{"type":"string","enum":["MOVE_DATE","PAUSE_ONE_MONTH","EXTEND_PLAN","PAY_WHAT_YOU_CAN"]}}}""";

    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

    static {
        add(new Tool(AgentTools.GET_HARDSHIP_SIGNALS, AgentTokens.SIGNALS_READ,
                "What the scorecard fired for this case, the level the code computed and the numbers behind each signal.",
                LOAN_ONLY));
        add(new Tool(AgentTools.GET_REPAYMENT_SCHEDULE, AgentTokens.SCHEDULE_READ,
                "The current schedule version: per instalment the due date, amount, paid and remaining cents.", LOAN_ONLY));
        add(new Tool(AgentTools.LIST_OFFERED_OPTIONS, AgentTokens.PREVIEW_RUN,
                "Every option on the member's offer, previewed by the schedule engine, with the option the code recommends.",
                LOAN_ONLY));
        add(new Tool(AgentTools.PREVIEW_OPTION, AgentTokens.PREVIEW_RUN,
                "One offered option in detail. The code chose its arguments; asking for an option that is not offered is an error.",
                PREVIEW));
        add(new Tool(AgentTools.GET_LOAN_SUMMARY, AgentTokens.LOAN_READ,
                "Principal, outstanding balance, status, late bucket and days past due.", LOAN_ONLY));
        add(new Tool(AgentTools.GET_LOAN_AGING, AgentTokens.LOAN_READ,
                "The instalment that matters next, days until it is due, when grace ends and how many were missed.", LOAN_ONLY));
        add(new Tool(AgentTools.GET_INCOME_SUMMARY, AgentTokens.INCOME_READ,
                "The member's consented weekly income in whole dollars. Not found without consent.", LOAN_ONLY));
        add(new Tool(AgentTools.LIST_PRIOR_CASES, AgentTokens.SIGNALS_READ,
                "Earlier hardship cases on this loan and how they ended.", LOAN_ONLY));
    }

    private ToolCatalogue() {
    }

    private static void add(Tool tool) {
        TOOLS.put(tool.name(), tool);
    }

    public static Optional<Tool> find(String name) {
        return Optional.ofNullable(TOOLS.get(name));
    }

    public static List<Tool> all() {
        return List.copyOf(TOOLS.values());
    }

    public static List<ToolSpec> specs() {
        return TOOLS.values().stream().map(Tool::spec).toList();
    }
}
