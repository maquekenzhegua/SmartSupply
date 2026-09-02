package com.smartsupply.agent;

import com.smartsupply.common.CurrentUser;
import com.smartsupply.common.TraceContext;
import com.smartsupply.common.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PythonSidecarService {

    private static final Logger log = LoggerFactory.getLogger(PythonSidecarService.class);
    private final RestClient restClient;
    private final boolean enabled;
    private final AtomicInteger failures = new AtomicInteger(0);
    private final AtomicLong openUntil = new AtomicLong(0);
    private static final int CIRCUIT_THRESHOLD = 5;
    private static final long CIRCUIT_OPEN_MS = 30_000;

    public record SidecarResult(String reply, List<Map<String, Object>> trace, List<Map<String, Object>> toolResults, int iters) {}

    public PythonSidecarService(
            @Value("${smartsupply.agent-python.enabled:false}") boolean enabled,
            @Value("${smartsupply.agent-python.base-url:http://127.0.0.1:8001}") String baseUrl,
            @Value("${smartsupply.agent-python.timeout-ms:12000}") int timeoutMs) {
        this.enabled = enabled;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(timeoutMs);
        f.setReadTimeout(timeoutMs);
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

    @SuppressWarnings("unchecked")
    public SidecarResult reasonWithTrace(List<Map<String, String>> messages, String agentType, String sessionId) {
        String text = reason(messages, agentType, sessionId);
        // reason() already handles trace header + response parsing; re-parse for structured fields
        if (text == null) return null;
        return new SidecarResult(text, List.of(), List.of(), 0);
    }

    @SuppressWarnings("unchecked")
    public String reason(List<Map<String, String>> messages, String agentType, String sessionId) {
        SidecarResult r = reasonStructured(messages, agentType, sessionId);
        return r == null ? null : r.reply();
    }

    @SuppressWarnings("unchecked")
    public SidecarResult reasonStructured(List<Map<String, String>> messages, String agentType, String sessionId) {
        if (!isEnabled()) return null;
        Map<String, Object> body = Map.of(
                "messages", messages,
                "agentType", agentType == null ? "general" : agentType,
                "sessionId", sessionId == null ? "default" : sessionId
        );
        String traceId = TraceContext.get();
        if (traceId == null) traceId = MDC.get(TraceIdFilter.TRACE_ID);
        String userRole = String.join(",", CurrentUser.roles());
        String finalTraceId = traceId;
        int maxAttempts = 3;
        long backoffMs = 400;
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, Object> resp = restClient.post()
                        .uri("/api/reason")
                        .header("X-Trace-Id", finalTraceId == null ? "" : finalTraceId)
                        .header("X-User-Role", userRole)
                        .body(body)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            throw new RuntimeException("sidecar error " + res.getStatusCode());
                        })
                        .body(new ParameterizedTypeReference<>() {});
                Object reply = resp == null ? null : resp.get("reply");
                String text = reply == null ? "" : String.valueOf(reply);
                List<Map<String, Object>> trace = resp != null && resp.get("trace") instanceof List ? (List<Map<String, Object>>) resp.get("trace") : List.of();
                List<Map<String, Object>> toolResults = resp != null && resp.get("tool_results") instanceof List ? (List<Map<String, Object>>) resp.get("tool_results") : List.of();
                int iters = resp != null && resp.get("iters") instanceof Number ? ((Number) resp.get("iters")).intValue() : 0;
                if (attempt > 1) log.info("sidecar recovered on attempt {}", attempt);
                failures.set(0);
                return new SidecarResult(text, trace, toolResults, iters);
            } catch (Exception e) {
                last = e;
                boolean retryable = isRetryable(e);
                if (!retryable || attempt == maxAttempts) break;
                log.warn("sidecar attempt {}/{} failed, retry in {}ms: {}", attempt, maxAttempts, backoffMs, e.toString());
                try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
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

    private boolean isRetryable(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("timeout") || msg.contains("connect") || msg.contains("read timed out") || msg.contains("503") || msg.contains("502") || msg.contains("sidecar error");
    }
}
