package com.smartsupply.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Java -> Python LangGraph 边车，带超时 + 指数退避 + 熔断降级。
 */
@Service
public class PythonSidecarService {

    private static final Logger log = LoggerFactory.getLogger(PythonSidecarService.class);
    private final RestClient restClient;
    private final boolean enabled;
    private final AtomicInteger failures = new AtomicInteger(0);
    private final AtomicLong openUntil = new AtomicLong(0);
    private static final int CIRCUIT_THRESHOLD = 5;
    private static final long CIRCUIT_OPEN_MS = 30_000;

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
    public String reason(List<Map<String, String>> messages, String agentType, String sessionId) {
        if (!isEnabled()) return null;
        Map<String, Object> body = Map.of(
                "messages", messages,
                "agentType", agentType == null ? "general" : agentType,
                "sessionId", sessionId == null ? "default" : sessionId
        );
        int maxAttempts = 3;
        long backoffMs = 400;
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, Object> resp = restClient.post()
                        .uri("/api/reason")
                        .body(body)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            throw new RuntimeException("sidecar error " + res.getStatusCode());
                        })
                        .body(new ParameterizedTypeReference<>() {});
                Object reply = resp == null ? null : resp.get("reply");
                String text = reply == null ? "" : String.valueOf(reply);
                if (attempt > 1) log.info("sidecar recovered on attempt {}", attempt);
                failures.set(0);
                return text;
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
