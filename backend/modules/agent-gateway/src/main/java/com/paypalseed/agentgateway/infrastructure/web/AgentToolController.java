package com.paypalseed.agentgateway.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paypalseed.agentgateway.api.AgentPrincipal;
import com.paypalseed.agentgateway.api.AgentTools;
import com.paypalseed.agentgateway.api.ProposalIntake.FailedCheck;
import com.paypalseed.agentgateway.api.ProposalIntake.Receipt;
import com.paypalseed.agentgateway.application.usecases.AgentToolService;
import com.paypalseed.agentgateway.application.usecases.ProposalIntakeService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The HTTP face of the read tools and of proposal intake ({@code agent-tools.yaml}), for agents running outside the JVM. */
@RestController
class AgentToolController {

    /** The {@code ProposalReceipt} shape of agent-tools.yaml. */
    record ReceiptView(UUID proposalId, String status, VerdictView verdict, boolean templateFallback) {
    }

    record VerdictView(String result, int checks, List<FailedCheck> failed) {
    }

    private final AgentToolService tools;
    private final ProposalIntakeService intake;
    private final ObjectMapper json;

    AgentToolController(AgentToolService tools, ProposalIntakeService intake, ObjectMapper json) {
        this.tools = tools;
        this.intake = intake;
        this.json = json;
    }

    @GetMapping("/agent/v1/tools/loan-summary")
    Object loanSummary(AgentPrincipal agent, @RequestParam UUID loanId) {
        return tools.execute(agent, AgentTools.GET_LOAN_SUMMARY, loanArgs(loanId));
    }

    @GetMapping("/agent/v1/tools/repayment-schedule")
    Object schedule(AgentPrincipal agent, @RequestParam UUID loanId) {
        return tools.execute(agent, AgentTools.GET_REPAYMENT_SCHEDULE, loanArgs(loanId));
    }

    @GetMapping("/agent/v1/tools/loan-aging")
    Object aging(AgentPrincipal agent, @RequestParam UUID loanId) {
        return tools.execute(agent, AgentTools.GET_LOAN_AGING, loanArgs(loanId));
    }

    @GetMapping("/agent/v1/tools/hardship-signals")
    Object signals(AgentPrincipal agent, @RequestParam UUID loanId) {
        return tools.execute(agent, AgentTools.GET_HARDSHIP_SIGNALS, loanArgs(loanId));
    }

    @GetMapping("/agent/v1/tools/income-summary")
    Object income(AgentPrincipal agent, @RequestParam UUID loanId) {
        return tools.execute(agent, AgentTools.GET_INCOME_SUMMARY, loanArgs(loanId));
    }

    @GetMapping("/agent/v1/tools/offered-options")
    Object offeredOptions(AgentPrincipal agent, @RequestParam UUID loanId) {
        return tools.execute(agent, AgentTools.LIST_OFFERED_OPTIONS, loanArgs(loanId));
    }

    @PostMapping("/agent/v1/tools/preview-option")
    Object previewOption(AgentPrincipal agent, @RequestBody ObjectNode body) {
        return tools.execute(agent, AgentTools.PREVIEW_OPTION, body);
    }

    @GetMapping("/agent/v1/tools/prior-cases")
    Object priorCases(AgentPrincipal agent, @RequestParam UUID loanId) {
        return tools.execute(agent, AgentTools.LIST_PRIOR_CASES, loanArgs(loanId));
    }

    /** 202 with the verdict; a replay of the same key and body answers 200 with the first receipt. */
    @PostMapping("/agent/v1/proposals")
    ResponseEntity<ReceiptView> submit(AgentPrincipal agent, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                       @RequestBody String body) {
        Receipt receipt = intake.submit(agent, key, body);
        ReceiptView view = new ReceiptView(receipt.proposalId(), receipt.status(),
                new VerdictView(receipt.result(), receipt.checks(), receipt.failed()), receipt.templateFallback());
        if (receipt.replay()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(view);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    private ObjectNode loanArgs(UUID loanId) {
        return json.createObjectNode().put("loanId", loanId.toString());
    }
}
