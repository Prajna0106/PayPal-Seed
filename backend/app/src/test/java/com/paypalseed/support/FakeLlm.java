package com.paypalseed.support;

import com.paypalseed.agentgateway.api.llm.LlmClient;
import com.paypalseed.agentgateway.api.llm.LlmException;
import com.paypalseed.agentgateway.api.llm.ModelRequest;
import com.paypalseed.agentgateway.api.llm.ModelResponse;
import com.paypalseed.agentgateway.api.llm.ToolCall;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * A scriptable model provider standing in for OpenRouter. Answers are taken from a queue in order; when
 * the queue is empty the default answerer (if any) is used, otherwise the call fails loudly so a test
 * never passes on an answer it did not script. Every call is recorded with the chain it was offered.
 */
public class FakeLlm implements LlmClient {

    public record Call(ModelRequest request, List<String> models) {
    }

    public static final long COST_MICRO_USD = 1_000;

    private final Queue<BiFunction<ModelRequest, List<String>, ModelResponse>> script = new ConcurrentLinkedQueue<>();
    private volatile BiFunction<ModelRequest, List<String>, ModelResponse> fallback;
    private final AtomicInteger ids = new AtomicInteger();
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    public void reset() {
        script.clear();
        fallback = null;
        calls.clear();
    }

    /** The next call answers with this content (served by the first model of the chain). */
    public FakeLlm reply(String content) {
        script.add((request, models) -> response(models.get(0), content, List.of()));
        return this;
    }

    /** The next call answers as if served by {@code model}, for example a fallback further down the chain. */
    public FakeLlm replyFrom(String model, String content) {
        script.add((request, models) -> response(model, content, List.of()));
        return this;
    }

    /** The next call asks for these tools. */
    public FakeLlm replyToolCalls(ToolCall... toolCalls) {
        script.add((request, models) -> response(models.get(0), null, List.of(toolCalls)));
        return this;
    }

    /** The next {@code times} calls fail with {@code error}. */
    public FakeLlm fail(LlmException error, int times) {
        for (int i = 0; i < times; i++) {
            script.add((request, models) -> {
                throw error;
            });
        }
        return this;
    }

    public FakeLlm failRetryable(int times) {
        return fail(new LlmException(LlmException.Kind.RETRYABLE, 503, "fake provider 503"), times);
    }

    /** Answers every call not covered by the queue. */
    public FakeLlm answerWith(BiFunction<ModelRequest, List<String>, ModelResponse> answerer) {
        this.fallback = answerer;
        return this;
    }

    @Override
    public ModelResponse complete(ModelRequest request, List<String> models) {
        calls.add(new Call(request, List.copyOf(models)));
        BiFunction<ModelRequest, List<String>, ModelResponse> next = script.poll();
        if (next == null) {
            next = fallback;
        }
        if (next == null) {
            throw new LlmException(LlmException.Kind.PERMANENT, 400, "FakeLlm has no scripted answer for " + request.purpose());
        }
        return next.apply(request, models);
    }

    public ModelResponse response(String model, String content, List<ToolCall> toolCalls) {
        return new ModelResponse(model, content, toolCalls, 100, 20, COST_MICRO_USD, 5, toolCalls.isEmpty() ? "stop" : "tool_calls");
    }

    public String nextToolCallId() {
        return "call_" + ids.incrementAndGet();
    }
}
