package com.smartsupply.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;

import com.smartsupply.common.TokenContext;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * muse-spark 专属 ChatModel：对接 opencode zen/go 的 /responses 协议。
 * 精度：优先解析 usage input_tokens/output_tokens 回填 actual，缺失则按长度估算 estimated；每次调用落地到 ObservationService 指标。
 */
public class MuseSparkChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(MuseSparkChatModel.class);
    // opencode zen/go 网关要求每次对话携带稳定会话头（缺失 400 MissingSessionID）
    private final String opencodeSession = "smartsupply-jvm-" + java.util.UUID.randomUUID();
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final HttpClient http;
    private final ObjectMapper om = new ObjectMapper();

    public MuseSparkChatModel(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * 唯一的用量读取口：从请求线程/流式回调线程的 ThreadLocal 取（写入与读取在同一线程），
     * 调用方必须一次消费完 prompt+completion。此前的 static AtomicLong 兜底是跨请求共享可变状态，
     * 并发下会把 A 请求的 token 记到 B 请求，已移除；取不到即返回 null，由调用方回退估算。
     */
    public static TokenContext.Usage consumeUsage() {
        return TokenContext.consume();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long start = System.currentTimeMillis();
        String input = buildInput(prompt);
        String body;
        try {
            var payload = om.createObjectNode();
            payload.put("model", model);
            payload.put("input", input);
            payload.put("max_output_tokens", 2500);
            var reasoning = om.createObjectNode();
            reasoning.put("effort", "low");
            payload.set("reasoning", reasoning);
            body = om.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("muse payload build failed", e);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/responses"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("x-opencode-session", opencodeSession)
                .header("User-Agent", "smartsupply-agent/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("muse /responses {} body={}", resp.statusCode(), resp.body());
                throw new RuntimeException("muse /responses failed " + resp.statusCode() + ": " + resp.body());
            }
            String text = extractOutputText(resp.body());
            if (text == null || text.isBlank()) {
                JsonNode root = om.readTree(resp.body());
                String status = root.path("status").asText();
                log.warn("muse empty output status={} body={}", status, resp.body());
                text = status.equals("incomplete") ? "[muse 推理截断，请重试或调大 max_output_tokens]" : "[muse 无输出]";
            }
            int promptTokens = estimateTokens(input);
            int completionTokens = estimateTokens(text);
            String source = "estimated";
            try {
                JsonNode root = om.readTree(resp.body());
                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    int pt = usage.path("input_tokens").asInt(usage.path("prompt_tokens").asInt(0));
                    int ct = usage.path("output_tokens").asInt(usage.path("completion_tokens").asInt(0));
                    if (pt > 0) { promptTokens = pt; source = "actual"; }
                    if (ct > 0) { completionTokens = ct; source = "actual"; }
                }
            } catch (Exception ignored) {}
            TokenContext.set(promptTokens, completionTokens, source);
            long latency = System.currentTimeMillis() - start;
            log.info("muse call model={} latencyMs={} promptTokens~{} completionTokens~{} source={} traceInputLen={}", model, latency, promptTokens, completionTokens, source, input.length());
            AssistantMessage msg = new AssistantMessage(text);
            msg.getMetadata().put("promptTokens", promptTokens);
            msg.getMetadata().put("completionTokens", completionTokens);
            msg.getMetadata().put("tokenSource", source);
            return new ChatResponse(List.of(new Generation(msg)));
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("muse call failed: " + e.getMessage(), e);
        }
    }

    private String buildInput(Prompt prompt) {
        List<String> parts = new ArrayList<>();
        prompt.getInstructions().forEach(m -> {
            String txt = m.getText();
            if (txt != null && !txt.isBlank()) parts.add(m.getMessageType().name() + ": " + txt);
        });
        return String.join("\n\n", parts).trim();
    }

    private String extractOutputText(String json) throws Exception {
        JsonNode root = om.readTree(json);
        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode node : output) {
                if ("message".equals(node.path("type").asText()) && "assistant".equals(node.path("role").asText())) {
                    JsonNode content = node.path("content");
                    if (content.isArray() && content.size() > 0) {
                        String t = content.get(0).path("text").asText(null);
                        if (t != null && !t.isBlank()) return t.trim();
                    }
                }
            }
        }
        String ot = root.path("output_text").asText(null);
        if (ot != null && !ot.isBlank()) return ot.trim();
        return null;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, text.length() / 3);
    }

    /**
     * 真流式：/responses + stream=true 的 SSE，逐 delta 产出 ChatResponse，
     * response.completed 事件回填 actual usage（缺失则 estimated），供 ObservationService 对账。
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String input = buildInput(prompt);
        return Flux.<ChatResponse>create(sink -> {
            try {
                var payload = om.createObjectNode();
                payload.put("model", model);
                payload.put("input", input);
                payload.put("max_output_tokens", 2500);
                payload.put("stream", true);
                var reasoning = om.createObjectNode();
                reasoning.put("effort", "low");
                payload.set("reasoning", reasoning);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/responses"))
                        .timeout(Duration.ofSeconds(120))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .header("x-opencode-session", opencodeSession)
                        .header("User-Agent", "smartsupply-agent/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(payload)))
                        .build();
                HttpResponse<java.util.stream.Stream<String>> resp =
                        http.send(req, HttpResponse.BodyHandlers.ofLines());
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    sink.error(new RuntimeException("muse /responses(stream) failed " + resp.statusCode()));
                    return;
                }
                AtomicLong promptTok = new AtomicLong(0);
                AtomicLong completionTok = new AtomicLong(0);
                resp.body().forEach(line -> {
                    if (sink.isCancelled()) return;
                    String l = line == null ? "" : line.trim();
                    if (!l.startsWith("data:")) return;
                    String data = l.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) return;
                    try {
                        JsonNode node = om.readTree(data);
                        String type = node.path("type").asText("");
                        switch (type) {
                            case "response.output_text.delta" -> {
                                String delta = node.path("delta").asText("");
                                if (!delta.isEmpty()) {
                                    completionTok.addAndGet(estimateTokens(delta));
                                    sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage(delta)))));
                                }
                            }
                            case "response.completed" -> {
                                JsonNode usage = node.path("response").path("usage");
                                long pt = usage.path("input_tokens").asLong(usage.path("prompt_tokens").asLong(0));
                                long ct = usage.path("output_tokens").asLong(usage.path("completion_tokens").asLong(0));
                                if (pt > 0) promptTok.set(pt);
                                if (ct > 0) completionTok.set(ct);
                            }
                            case "response.failed", "error" ->
                                    sink.error(new RuntimeException("muse stream " + type + ": " + node));
                            default -> { }
                        }
                    } catch (Exception ignored) { }
                });
                long pt = promptTok.get() > 0 ? promptTok.get() : estimateTokens(input);
                long ct = completionTok.get();
                String src = promptTok.get() > 0 ? "actual" : "estimated";
                TokenContext.set((int) pt, (int) ct, src);
                log.info("muse stream model={} promptTokens~{} completionTokens~{} source={}", model, pt, ct, src);
                sink.complete();
            } catch (Exception e) {
                sink.error(new RuntimeException("muse stream failed: " + e.getMessage(), e));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public ChatOptions getDefaultOptions() { return null; }
}
