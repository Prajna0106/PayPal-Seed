package com.paypalseed.agentgateway.infrastructure.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.agentgateway.api.llm.ChatMessage;
import com.paypalseed.agentgateway.api.llm.LlmException;
import com.paypalseed.agentgateway.api.llm.ModelRequest;
import com.paypalseed.agentgateway.api.llm.ModelResponse;
import com.paypalseed.agentgateway.api.llm.ToolCall;
import com.paypalseed.agentgateway.api.llm.ToolSpec;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The OpenRouter adapter against a local stub server: the exact request it sends and how it reads answers. */
class OpenRouterLlmClientTest {

    private static final String SCHEMA = """
            {"type":"object","properties":{"category":{"type":"string"}},"required":["category"],"additionalProperties":false}""";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final Map<String, String> requestHeaders = new ConcurrentHashMap<>();
    private volatile int status = 200;
    private volatile String responseBody;
    private volatile long delayMs;
    private HttpServer server;
    private OpenRouterLlmClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getRequestHeaders().forEach((k, v) -> requestHeaders.put(k.toLowerCase(), v.get(0)));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        client = new OpenRouterLlmClient(HttpClient.newHttpClient(), json,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/", "sk-test-not-real", "https://seed.test");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsTheGuardedRequestShape() throws Exception {
        responseBody = """
                {"id":"gen-1","model":"anthropic/claude-3.5-haiku","choices":[{"finish_reason":"stop",
                 "message":{"role":"assistant","content":"{\\"category\\":\\"JOB_LOSS\\"}"}}],
                 "usage":{"prompt_tokens":120,"completion_tokens":9,"cost":0.000321}}""";

        ModelResponse response = client.complete(request(true, false), List.of("openai/gpt-4o-mini", "anthropic/claude-3.5-haiku"));

        JsonNode body = json.readTree(requestBody.get());
        assertThat(body.has("model")).isFalse();
        assertThat(body.path("models").toString()).isEqualTo("[\"openai/gpt-4o-mini\",\"anthropic/claude-3.5-haiku\"]");
        assertThat(body.path("temperature").asInt()).isZero();
        assertThat(body.path("seed").asInt()).isEqualTo(OpenRouterLlmClient.SEED);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(64);
        assertThat(body.path("provider").path("data_collection").asText()).isEqualTo("deny");
        assertThat(body.path("provider").path("require_parameters").asBoolean()).isTrue();
        assertThat(body.path("usage").path("include").asBoolean()).isTrue();
        JsonNode format = body.path("response_format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("json_schema").path("name").asText()).isEqualTo("label");
        assertThat(format.path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(format.path("json_schema").path("schema").path("required").get(0).asText()).isEqualTo("category");
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.has("tools")).isFalse();
        assertThat(requestHeaders).containsEntry("authorization", "Bearer sk-test-not-real")
                .containsEntry("x-title", "PayPal Seed").containsEntry("http-referer", "https://seed.test");

        assertThat(response.model()).isEqualTo("anthropic/claude-3.5-haiku");
        assertThat(response.content()).isEqualTo("{\"category\":\"JOB_LOSS\"}");
        assertThat(response.tokensIn()).isEqualTo(120);
        assertThat(response.tokensOut()).isEqualTo(9);
        assertThat(response.costMicroUsd()).isEqualTo(321);
        assertThat(response.finishReason()).isEqualTo("stop");
    }

    @Test
    void sendsToolsAndToolTurnsAndReadsToolCalls() throws Exception {
        responseBody = """
                {"model":"openai/gpt-4o-mini","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                 "content":null,"tool_calls":[{"id":"call_1","type":"function",
                 "function":{"name":"get_loan_aging","arguments":"{\\"loanId\\":\\"x\\"}"}}]}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}""";

        ModelResponse response = client.complete(request(false, true), List.of("openai/gpt-4o-mini"));

        JsonNode body = json.readTree(requestBody.get());
        assertThat(body.path("model").asText()).isEqualTo("openai/gpt-4o-mini");
        assertThat(body.has("models")).isFalse();
        assertThat(body.path("tool_choice").asText()).isEqualTo("auto");
        JsonNode tool = body.path("tools").get(0);
        assertThat(tool.path("type").asText()).isEqualTo("function");
        assertThat(tool.path("function").path("name").asText()).isEqualTo("get_loan_aging");
        assertThat(tool.path("function").path("parameters").path("type").asText()).isEqualTo("object");
        JsonNode assistant = body.path("messages").get(2);
        assertThat(assistant.path("tool_calls").get(0).path("function").path("arguments").asText()).isEqualTo("{}");
        JsonNode toolTurn = body.path("messages").get(3);
        assertThat(toolTurn.path("role").asText()).isEqualTo("tool");
        assertThat(toolTurn.path("tool_call_id").asText()).isEqualTo("call_0");

        assertThat(response.hasToolCalls()).isTrue();
        assertThat(response.toolCalls()).containsExactly(new ToolCall("call_1", "get_loan_aging", "{\"loanId\":\"x\"}"));
        assertThat(response.content()).isNull();
        assertThat(response.costMicroUsd()).isZero();
    }

    @Test
    void rateLimitsAndServerErrorsAreRetryableClientErrorsAreNot() {
        responseBody = "{\"error\":{\"code\":429,\"message\":\"slow down\"}}";
        status = 429;
        assertThatThrownBy(() -> client.complete(request(false, false), List.of("m")))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.status()).isEqualTo(429);
                    assertThat(e.getMessage()).doesNotContain("slow down");
                });
        status = 503;
        assertThatThrownBy(() -> client.complete(request(false, false), List.of("m")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isTrue());
        status = 402;
        assertThatThrownBy(() -> client.complete(request(false, false), List.of("m")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.PERMANENT));
    }

    @Test
    void anErrorInsideA200IsAFailureAndAnEmptyAnswerIsRetryable() {
        responseBody = "{\"error\":{\"code\":502,\"message\":\"upstream\"}}";
        assertThatThrownBy(() -> client.complete(request(false, false), List.of("m")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isTrue());
        responseBody = "{\"choices\":[]}";
        assertThatThrownBy(() -> client.complete(request(false, false), List.of("m")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isTrue());
        responseBody = "not json";
        assertThatThrownBy(() -> client.complete(request(false, false), List.of("m")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void aSlowProviderTimesOutAsRetryable() {
        responseBody = "{}";
        delayMs = 1500;
        ModelRequest slow = request(false, false).withTimeout(Duration.ofMillis(200));
        assertThatThrownBy(() -> client.complete(slow, List.of("m")))
                .isInstanceOfSatisfying(LlmException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.status()).isEqualTo(408);
                });
    }

    @Test
    void refusesToStartWithoutAKey() {
        assertThatThrownBy(() -> new OpenRouterLlmClient(HttpClient.newHttpClient(), json, "http://x", " ", "u"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ModelRequest request(boolean schema, boolean tools) {
        List<ChatMessage> messages = tools
                ? List.of(ChatMessage.system("You are careful."), ChatMessage.user("Assess the loan."),
                        ChatMessage.assistant(null, List.of(new ToolCall("call_0", "get_loan_aging", null))),
                        ChatMessage.tool("call_0", "{\"dpd\":0}"))
                : List.of(ChatMessage.system("Label it."), ChatMessage.user("text"));
        List<ToolSpec> specs = tools
                ? List.of(new ToolSpec("get_loan_aging", "Aging of the loan", "{\"type\":\"object\",\"properties\":{}}"))
                : List.of();
        return new ModelRequest("guardian", UUID.randomUUID(), "EXTRACT", messages, specs, schema ? "label" : null,
                schema ? SCHEMA : null, 64, Duration.ofSeconds(5));
    }
}
