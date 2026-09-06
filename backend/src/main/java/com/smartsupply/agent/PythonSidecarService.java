package com.smartsupply.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartsupply.common.TraceContext;
import com.smartsupply.common.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PythonSidecarService {

    private static final Logger log = LoggerFactory.getLogger(PythonSidecarService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String baseUrl;
    private final boolean enabled;
    private final String apiKey;
    private final AtomicInteger failures = new AtomicInteger(0);
    private final AtomicLong openUntil = new AtomicLong(0);
    private static final int CIRCUIT_THRESHOLD = 5;
    private static final long CIRCUIT_OPEN_MS = 30_000;

    /**
     * degraded=true 表示边车"完成了流程但结果降级"（LLM 不可用 / 工具全部失败等），
     * 响应仍可用但不得被当成正常模型推理计入评测与台账；degradeReason 为机器可读原因。
     * interrupted=true 表示写闸门挂起等人工批准（langgraph interrupt）：reply 为空，
     * confirm 为待批准动作，threadId 必须随批准/拒绝请求原样带回以恢复执行。
     */
    public record SidecarResult(String reply, List<Map<String, Object>> trace, List<Map<String, Object>> toolResults,
                                int iters, boolean degraded, String degradeReason, TokenUsage usage,
                                String threadId, boolean interrupted, List<Map<String, Object>> confirm) {
        public SidecarResult(String reply, List<Map<String, Object>> trace, List<Map<String, Object>> toolResults,
                             int iters, boolean degraded, String degradeReason, TokenUsage usage) {
            this(reply, trace, toolResults, iters, degraded, degradeReason, usage, "", false, List.of());
        }
    }

    /** 边车回传的真实 provider 用量；null/0 值由调用方回退估算 */
    public record TokenUsage(int promptTokens, int completionTokens, String source) {}

    public PythonSidecarService(
            @Value("${smartsupply.agent-python.enabled:false}") boolean enabled,
            @Value("${smartsupply.agent-python.base-url:http://127.0.0.1:8001}") String baseUrl,
            @Value("${smartsupply.agent-python.timeout-ms:90000}") int timeoutMs,
            @Value("${smartsupply.agent-python.blocking-timeout-ms:300000}") int blockingTimeoutMs,
            @Value("${smartsupply.agent-python.api-key:}") String apiKey) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);            // 连接快速失败，交给重试/熔断
        // 非流式 /api/reason 在真实推理模型下单次可到 1~4 分钟（curl 实测 46s，慢时更久）；
        // 90s 读超时会以"extracting response"的截断形态失败（绕过 isRetryable），必须独立放宽
        f.setReadTimeout(blockingTimeoutMs);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(f)
                .build();
    }

    public boolean isEnabled() {
        if (!enabled) return false;
        long until = openUntil.get();
        if (until > System.currentTimeMillis()) return false;
        if (until != 0) {
            openUntil.set(0);
            failures.set(0);
        }
        return true;
    }

    public String reason(List<Map<String, String>> messages, String agentType, String sessionId, String authorization) {
        SidecarResult r = reasonStructured(messages, agentType, sessionId, authorization, "", null);
        return r == null ? null : r.reply();
    }

    public SidecarResult reasonStructured(List<Map<String, String>> messages, String agentType, String sessionId, String authorization) {
        return reasonStructured(messages, agentType, sessionId, authorization, "", null);
    }

    /**
     * authorization：发起本次对话的真实用户 JWT（来自 /api/agent/chat[/stream] 请求头）。
     * 透传给边车后，边车回环调用 Java 只读工具时会带上同一 Bearer，
     * 使 CurrentUser/审计在深度模式下仍归属真实用户。为空时边车回退服务账号 token。
     *
     * 返回语义：
     *  - null          边车不可用（熔断/网络/非降级类 5xx）→ 调用方降级 java-direct
     *  - degraded=true 边车可用但结果降级（4xx 参数错误 / 边车显式 degraded）→ 调用方须如实上报
     */
    @SuppressWarnings("unchecked")
    public SidecarResult reasonStructured(List<Map<String, String>> messages, String agentType, String sessionId,
                                          String authorization, String threadId, Map<String, Object> resume) {
        if (!isEnabled()) return null;
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("messages", messages);
        body.put("agentType", agentType == null ? "general" : agentType);
        body.put("sessionId", sessionId == null ? "default" : sessionId);
        if (threadId != null && !threadId.isBlank()) body.put("threadId", threadId);
        if (resume != null) body.put("resume", resume);
        String traceId = TraceContext.get();
        if (traceId == null) traceId = MDC.get(TraceIdFilter.TRACE_ID);
        String finalTraceId = traceId;
        String authHeader = authorization;
        int maxAttempts = 3;
        long backoffMs = 400;
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, Object> resp = restClient.post()
                        .uri("/api/reason")
                        .header("X-Trace-Id", finalTraceId == null ? "" : finalTraceId)
                        .header("Authorization", authHeader == null ? "" : authHeader)
                        .header("X-Api-Key", apiKey == null ? "" : apiKey)
                        .body(body)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});
                String text = resp == null || resp.get("reply") == null ? "" : String.valueOf(resp.get("reply"));
                List<Map<String, Object>> trace = listOfMaps(resp, "trace");
                List<Map<String, Object>> toolResults = listOfMaps(resp, "tool_results");
                int iters = resp != null && resp.get("iters") instanceof Number n ? n.intValue() : 0;
                boolean degraded = resp != null && Boolean.TRUE.equals(resp.get("degraded"));
                String degradeReason = resp == null ? "" : String.valueOf(resp.getOrDefault("degrade_reason", ""));
                String respThreadId = resp == null ? "" : String.valueOf(resp.getOrDefault("threadId", ""));
                boolean interrupted = resp != null && Boolean.TRUE.equals(resp.get("interrupted"));
                List<Map<String, Object>> confirm = listOfMaps(resp, "confirm");
                if (degraded) {
                    log.warn("sidecar degraded reply (traceId={}, reason={})", finalTraceId, degradeReason);
                }
                failures.set(0);
                return new SidecarResult(text, trace, toolResults, iters, degraded, degradeReason, parseUsage(resp),
                        respThreadId, interrupted, confirm);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 400) {
                    // 请求本身有错：重试无意义，如实作为 degraded 结果上抛
                    return degradedError("sidecar_bad_request", e.getResponseBodyAsString());
                }
                if (e.getStatusCode().is5xxServerError() && isDegradedBody(e.getResponseBodyAsString())) {
                    // 边车显式降级（如 500 degraded）：不重试、不熔断，如实上报
                    return degradedError("sidecar_degraded_body", e.getResponseBodyAsString());
                }
                last = e;
                if (!isRetryable(e) || attempt == maxAttempts) break;
                sleepBackoff(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 4000);
            } catch (Exception e) {
                last = e;
                if (!isRetryable(e) || attempt == maxAttempts) break;
                sleepBackoff(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 4000);
            }
        }
        int f = failures.incrementAndGet();
        if (f >= CIRCUIT_THRESHOLD) {
            openUntil.set(System.currentTimeMillis() + CIRCUIT_OPEN_MS);
            log.warn("sidecar circuit OPEN for {}ms after {} failures: {}", CIRCUIT_OPEN_MS, f, last == null ? "unknown" : last.toString());
        } else {
            log.warn("sidecar all attempts failed (failures={}), degrade to Java direct: {}", f, last == null ? "unknown" : last.toString());
        }
        return null;
    }

    private static List<Map<String, Object>> listOfMaps(Map<String, Object> resp, String key) {
        Object v = resp == null ? null : resp.get(key);
        return v instanceof List ? (List<Map<String, Object>>) v : List.of();
    }

    private static boolean isDegradedBody(String responseBody) {
        try {
            Map<?, ?> m = MAPPER.readValue(responseBody, Map.class);
            return Boolean.TRUE.equals(m.get("degraded")) || Boolean.TRUE.equals(m.get("fallback"));
        } catch (Exception e) {
            return false;
        }
    }

    private SidecarResult degradedError(String reason, String responseBody) {
        String detail = responseBody == null ? "" : responseBody.substring(0, Math.min(200, responseBody.length()));
        log.warn("sidecar returned non-retryable error ({}): {}", reason, detail);
        return new SidecarResult("", List.of(), List.of(), 0, true, reason + ":" + detail, null);
    }

    @SuppressWarnings("unchecked")
    private static TokenUsage parseUsage(Map<String, Object> resp) {
        Object u = resp == null ? null : resp.get("usage");
        if (!(u instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) u;
        int pt = m.get("prompt_tokens") instanceof Number n ? n.intValue() : 0;
        int ct = m.get("completion_tokens") instanceof Number n ? n.intValue() : 0;
        String src = String.valueOf(m.getOrDefault("source", "estimated"));
        if (pt <= 0 && ct <= 0) return null;
        return new TokenUsage(pt, ct, src);
    }

    private void sleepBackoff(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private boolean isRetryable(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("timeout") || msg.contains("connect") || msg.contains("read timed out")
                || msg.contains("503") || msg.contains("502") || msg.contains("500 server error");
    }

    /**
     * 深度模式流式调用：消费边车 /api/reason/stream 的 SSE，逐事件回调 onEvent（实时转发给前端），
     * 结束后返回与 reasonStructured 同构的 SidecarResult（供落库与 done 事件汇总）。
     *
     * 事件契约（Python graph.stream_reasoning_events）：
     *   plan/reflect {calls, provider} | tool {tool,args,ok,error?} | reply {text} | done {degraded,...}
     * 流式不做多尝试重试（部分事件已发出后重试会造成重复下发）：
     *   - 连接/读失败 → 计熔断并返回 null（调用方回落 java-direct）
     *   - 4xx/边车降级体 → 返回 degraded 结果（如实上报，不回落）
     */
    public SidecarResult streamReason(List<Map<String, String>> messages, String agentType, String sessionId,
                                      String authorization, java.util.function.BiConsumer<String, Map<String, Object>> onEvent) {
        return streamReason(messages, agentType, sessionId, authorization, onEvent, "", null);
    }

    @SuppressWarnings("unchecked")
    public SidecarResult streamReason(List<Map<String, String>> messages, String agentType, String sessionId,
                                      String authorization, java.util.function.BiConsumer<String, Map<String, Object>> onEvent,
                                      String threadId, Map<String, Object> resume) {
        if (!isEnabled()) return null;
        String traceId = TraceContext.get();
        if (traceId == null) traceId = MDC.get(TraceIdFilter.TRACE_ID);
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("messages", messages);
            body.put("agentType", agentType == null ? "general" : agentType);
            body.put("sessionId", sessionId == null ? "default" : sessionId);
            if (threadId != null && !threadId.isBlank()) body.put("threadId", threadId);
            if (resume != null) body.put("resume", resume);
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(this.baseUrl.replaceAll("/$", "") + "/api/reason/stream"))
                    .timeout(java.time.Duration.ofSeconds(600))  // 多步 ReAct 全程（真实推理模型可到数分钟）
                    .header("Content-Type", "application/json")
                    .header("X-Trace-Id", traceId == null ? "" : traceId)
                    .header("Authorization", authorization == null ? "" : authorization)
                    .header("X-Api-Key", apiKey == null ? "" : apiKey)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            java.net.http.HttpResponse<java.util.stream.Stream<String>> resp =
                    STREAM_CLIENT.send(request, java.net.http.HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() == 400) {
                return degradedError("sidecar_bad_request", "stream http 400");
            }
            if (resp.statusCode() != 200) {
                log.warn("sidecar stream unexpected status {} from {}", resp.statusCode(), request.uri());
                throw new java.io.IOException("stream http " + resp.statusCode());
            }
            StringBuilder reply = new StringBuilder();
            List<Map<String, Object>> trace = new java.util.ArrayList<>();
            List<Map<String, Object>> toolResults = new java.util.ArrayList<>();
            boolean degraded = false;
            String degradeReason = "";
            int iters = 0;
            TokenUsage usage = null;
            boolean interrupted = false;
            String outThreadId = threadId == null ? "" : threadId;
            List<Map<String, Object>> confirm = new java.util.ArrayList<>();
            String event = "message";
            StringBuilder data = new StringBuilder();
            // 惰性逐行消费：collect(toList) 会把整条 SSE 缓冲到内存才开转发，"实时"是假的
            for (String line : (Iterable<String>) resp.body()::iterator) {
                if (line.startsWith("event:")) { event = line.substring(6).trim(); continue; }
                if (line.startsWith("data:")) { data.append(line.substring(5).replaceFirst("^ ", "")); continue; }
                if (!line.isBlank() || data.length() == 0) continue;
                // 空行 = 事件边界
                String payload = data.toString();
                data.setLength(0);
                Map<String, Object> parsed = parseMap(payload);
                switch (event) {
                    case "plan", "reflect" -> {
                        Map<String, Object> entry = new java.util.HashMap<>();
                        entry.put("node", event);
                        entry.put("provider", parsed.getOrDefault("provider", ""));
                        entry.put("calls", parsed.getOrDefault("calls", List.of()));
                        trace.add(entry);
                        acceptEvent(onEvent, event, parsed);
                    }
                    case "tool" -> {
                        Map<String, Object> entry = new java.util.HashMap<>(parsed);
                        entry.put("node", "tool");
                        trace.add(entry);
                        toolResults.add(new java.util.HashMap<>(Map.of(
                                "tool", String.valueOf(parsed.getOrDefault("tool", "")),
                                "args", parsed.getOrDefault("args", Map.of()),
                                "ok", parsed.getOrDefault("ok", true))));
                        acceptEvent(onEvent, event, parsed);
                    }
                    case "reply_delta" -> {
                        // 真流式：provider token delta 原样转发并累计成完整回复
                        String delta = String.valueOf(parsed.getOrDefault("text", ""));
                        reply.append(delta);
                        acceptEvent(onEvent, event, parsed);
                    }
                    case "reply" -> {
                        String text = String.valueOf(parsed.getOrDefault("text", ""));
                        reply.append(text);
                        acceptEvent(onEvent, event, parsed);
                    }
                    case "confirm_required" -> {
                        // 写闸门挂起：转发事件并记录待批准动作（thread_id 以 done/事件载荷为准）
                        Object tid = parsed.get("thread_id");
                        if (tid != null && !String.valueOf(tid).isBlank()) outThreadId = String.valueOf(tid);
                        if (parsed.get("tool") != null) confirm.add(new java.util.HashMap<>(parsed));
                        acceptEvent(onEvent, event, parsed);
                    }
                    case "done" -> {
                        degraded = Boolean.TRUE.equals(parsed.get("degraded"));
                        degradeReason = String.valueOf(parsed.getOrDefault("degrade_reason", ""));
                        iters = parsed.get("iters") instanceof Number n ? n.intValue() : 0;
                        usage = parseUsage(parsed);
                        interrupted = Boolean.TRUE.equals(parsed.get("interrupted"));
                        Object tid = parsed.get("thread_id");
                        if (tid != null && !String.valueOf(tid).isBlank()) outThreadId = String.valueOf(tid);
                        Object cfg = parsed.get("confirm");
                        if (cfg instanceof List<?> l) {
                            for (Object o : l) if (o instanceof Map) confirm.add((Map<String, Object>) o);
                        }
                    }
                    default -> { /* 未知事件忽略，保持前向兼容 */ }
                }
                event = "message";
            }
            failures.set(0);
            if (degraded) log.warn("sidecar stream degraded (reason={})", degradeReason);
            return new SidecarResult(reply.toString(), trace, toolResults, iters, degraded, degradeReason, usage,
                    outThreadId, interrupted, confirm);
        } catch (java.io.IOException e) {  // 含 HttpTimeoutException（其子类）
            int f = failures.incrementAndGet();
            if (f >= CIRCUIT_THRESHOLD) {
                openUntil.set(System.currentTimeMillis() + CIRCUIT_OPEN_MS);
                log.warn("sidecar stream circuit OPEN after {} failures: {}", f, e.toString());
            } else {
                log.warn("sidecar stream failed (failures={}), degrade to Java direct: {}", f, e.toString());
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void acceptEvent(java.util.function.BiConsumer<String, Map<String, Object>> onEvent,
                             String event, Map<String, Object> payload) {
        try { onEvent.accept(event, payload); } catch (Exception e) { log.warn("stream onEvent failed: {}", e.toString()); }
    }

    private static Map<String, Object> parseMap(String json) {
        try {
            return MAPPER.readValue(json == null || json.isBlank() ? "{}" : json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private final java.net.http.HttpClient STREAM_CLIENT = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)  // uvicorn/h11 拒绝 h2c Upgrade 请求（400），SSE 走 1.1
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();
}
