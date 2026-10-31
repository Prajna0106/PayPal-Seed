package com.paypalseed.agentgateway.infrastructure.clients;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paypalseed.agentgateway.api.llm.ChatMessage;
import com.paypalseed.agentgateway.api.llm.LlmClient;
import com.paypalseed.agentgateway.api.llm.LlmException;
import com.paypalseed.agentgateway.api.llm.ModelRequest;
import com.paypalseed.agentgateway.api.llm.ModelResponse;
import com.paypalseed.agentgateway.api.llm.ToolCall;
import com.paypalseed.agentgateway.api.llm.ToolSpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenRouter's OpenAI-compatible chat completions API (spike SH3 confirms the flags).
 *
 * <ul>
 *   <li>{@code models}: the fallback chain, so provider-level failover happens inside one request.</li>
 *   <li>{@code provider.data_collection = deny} and {@code require_parameters = true}: only providers that
 *       do not store or train on prompts, and that honour the structured-output and tool parameters.</li>
 *   <li>{@code temperature 0} and a fixed {@code seed}: as repeatable as the provider allows.</li>
 *   <li>{@code usage.include}: cost per call comes back with the answer and feeds the daily budget.</li>
 * </ul>
 * Message content and the API key are never logged.
 */
public final class OpenRouterLlmClient implements LlmClient {

    static final int SEED = 7;

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI endpoint;
    private final String apiKey;
    private final String appUrl;

    public OpenRouterLlmClient(HttpClient http, ObjectMapper json, String baseUrl, String apiKey, String appUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OpenRouter needs an API key");
        }
        this.http = http;
        this.json = json;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        this.apiKey = apiKey;
        this.appUrl = appUrl;
    }

    @Override
    public ModelResponse complete(ModelRequest request, List<String> models) {
        if (models.isEmpty()) {
            throw new IllegalArgumentException("at least one model is required");
        }
        long started = System.nanoTime();
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(request.timeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", appUrl)
                .header("X-Title", "PayPal Seed")
                .POST(HttpRequest.BodyPublishers.ofString(body(request, models)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new LlmException(LlmException.Kind.RETRYABLE, 408, "Model call timed out", e);
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.RETRYABLE, 0, "Model provider unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.RETRYABLE, 0, "Model call interrupted", e);
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        if (response.statusCode() / 100 != 2) {
            throw failure(response.statusCode(), response.body());
        }
        return parse(response.body(), latencyMs);
    }

    String body(ModelRequest request, List<String> models) {
        ObjectNode root = json.createObjectNode();
        if (models.size() == 1) {
            root.put("model", models.get(0));
        } else {
            ArrayNode chain = root.putArray("models");
            models.forEach(chain::add);
        }
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage m : request.messages()) {
            messages.add(message(m));
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSpec t : request.tools()) {
                ObjectNode function = tools.addObject().put("type", "function").putObject("function");
                function.put("name", t.name()).put("description", t.description());
                function.set("parameters", readJson(t.parametersJsonSchema(), "tool schema " + t.name()));
            }
            root.put("tool_choice", "auto");
        }
        if (request.jsonSchema() != null) {
            ObjectNode format = root.putObject("response_format").put("type", "json_schema");
            ObjectNode schema = format.putObject("json_schema").put("name", request.jsonSchemaName()).put("strict", true);
            schema.set("schema", readJson(request.jsonSchema(), "response schema"));
        }
        root.put("max_tokens", request.maxOutputTokens());
        root.put("temperature", 0);
        root.put("seed", SEED);
        root.putObject("provider").put("data_collection", "deny").put("require_parameters", true);
        root.putObject("usage").put("include", true);
        try {
            return json.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise a model request", e);
        }
    }

    private ObjectNode message(ChatMessage m) {
        ObjectNode node = json.createObjectNode().put("role", m.role());
        if (m.content() == null) {
            node.putNull("content");
        } else {
            node.put("content", m.content());
        }
        if (m.toolCallId() != null) {
            node.put("tool_call_id", m.toolCallId());
        }
        if (!m.toolCalls().isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall c : m.toolCalls()) {
                ObjectNode call = calls.addObject().put("id", c.id()).put("type", "function");
                call.putObject("function").put("name", c.name()).put("arguments", c.argumentsJson());
            }
        }
        return node;
    }

    ModelResponse parse(String body, long latencyMs) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JsonProcessingException e) {
            throw new LlmException(LlmException.Kind.RETRYABLE, 200, "Model provider returned invalid JSON", e);
        }
        if (root.hasNonNull("error")) {
            throw failure(root.path("error").path("code").asInt(502), body);
        }
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new LlmException(LlmException.Kind.RETRYABLE, 200, "Model provider returned no choices");
        }
        JsonNode message = choice.path("message");
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            toolCalls.add(new ToolCall(call.path("id").asText(), function.path("name").asText(),
                    function.path("arguments").asText("{}")));
        }
        JsonNode usage = root.path("usage");
        long costMicroUsd = Math.round(usage.path("cost").asDouble(0) * 1_000_000);
        String content = message.path("content").isTextual() ? message.path("content").asText() : null;
        return new ModelResponse(root.path("model").asText(null), content, toolCalls, usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0), costMicroUsd, latencyMs, choice.path("finish_reason").asText(null));
    }

    /** The provider's message is kept out of the exception: it can echo request content. */
    private static LlmException failure(int status, String body) {
        boolean retryable = status == 408 || status == 429 || status >= 500;
        return new LlmException(retryable ? LlmException.Kind.RETRYABLE : LlmException.Kind.PERMANENT, status,
                "Model provider answered HTTP " + status + " (" + (body == null ? 0 : body.length()) + " bytes)");
    }

    private JsonNode readJson(String text, String what) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(what + " is not valid JSON", e);
        }
    }

    static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();
    }
}
