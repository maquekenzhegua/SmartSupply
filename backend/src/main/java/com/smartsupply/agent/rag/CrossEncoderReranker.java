package com.smartsupply.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 可插拔 cross-encoder 重排：优先调 Python 边车 /api/rag/rerank，超时或失败自动回退到本地 BM25。
 * 面试话术：离线 BM25 保证可演示，生产切 bge-reranker/cross-encoder 只改边车权重，不改业务代码。
 */
@Component
public class CrossEncoderReranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);
    private final Reranker local;
    private final RestClient restClient;
    private final boolean enabled;
    private final AtomicInteger failures = new AtomicInteger(0);
    private final AtomicLong openUntil = new AtomicLong(0);
    private static final int THRESHOLD = 5;
    private static final long OPEN_MS = 30_000;

    public CrossEncoderReranker(Reranker local,
                                @Value("${smartsupply.agent-python.enabled:false}") boolean enabled,
                                @Value("${smartsupply.agent-python.base-url:http://127.0.0.1:8001}") String baseUrl,
                                @Value("${smartsupply.agent-python.timeout-ms:12000}") int timeoutMs) {
        this.local = local;
        this.enabled = enabled;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Math.min(3000, timeoutMs));
        f.setReadTimeout(Math.min(3000, timeoutMs));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    public record RerankResult(List<Document> docs, String mode, boolean fallback) {}

    public RerankResult rerank(String query, List<Document> docs, int topK, RerankMode mode) {
        if (docs == null || docs.isEmpty()) return new RerankResult(List.of(), "none", false);
        if (mode == RerankMode.BM25 || !enabled || isOpen()) {
            return new RerankResult(local.rerank(query, docs, topK), mode == RerankMode.BM25 ? "bm25-local" : "bm25-fallback", mode != RerankMode.BM25);
        }
        String pyMode = mode == RerankMode.CROSS_ENCODER ? "cross-encoder" : "auto";
        try {
            List<Map<String, Object>> payloadDocs = docs.stream().map(d -> {
                Map<String, Object> m = new HashMap<>();
                m.put("content", d.getText());
                Object title = d.getMetadata().get("title");
                if (title != null) m.put("title", String.valueOf(title));
                return m;
            }).collect(Collectors.toList());
            Map<String, Object> body = new HashMap<>();
            body.put("query", query == null ? "" : query);
            body.put("docs", payloadDocs);
            body.put("topK", topK);
            body.put("mode", pyMode);
            Map<String, Object> resp = restClient.post().uri("/api/rag/rerank").body(body)
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            Object reranked = resp == null ? null : resp.get("reranked");
            if (reranked instanceof List<?> list && !list.isEmpty()) {
                String rMode = String.valueOf(resp.getOrDefault("mode", "cross-encoder"));
                boolean fb = Boolean.TRUE.equals(resp.get("fallback"));
                List<Document> out = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map) {
                        Map<?,?> m = (Map<?,?>) o;
                        Object contentObj = m.get("content");
                        if (contentObj == null) contentObj = m.get("text");
                        String content = contentObj == null ? "" : String.valueOf(contentObj);
                        Document d = new Document(content, new HashMap<>());
                        Object t = m.get("title");
                        if (t != null) d.getMetadata().put("title", String.valueOf(t));
                        out.add(d);
                    }
                }
                if (!out.isEmpty()) {
                    failures.set(0);
                    return new RerankResult(out, rMode, fb);
                }
            }
        } catch (Exception e) {
            log.debug("cross-encoder rerank fallback to BM25: {}", e.toString());
            int f = failures.incrementAndGet();
            if (f >= THRESHOLD) openUntil.set(System.currentTimeMillis() + OPEN_MS);
        }
        return new RerankResult(local.rerank(query, docs, topK), "bm25-fallback", true);
    }

    private boolean isOpen() {
        long until = openUntil.get();
        if (until > System.currentTimeMillis()) return true;
        if (until != 0) { openUntil.set(0); failures.set(0); }
        return false;
    }
}
