package com.smartsupply.agent.rag;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 服务：向量检索 + 关键词兜底 + 双轨重排，分段入库保证中文长文召回。
 * 切片策略：TextSplitter 800/100 滑动窗口，中文句号友好；面试可讲窗口与 overlap 的权衡。
 * 召回：vector topK=8 -> 双轨重排(CrossEncoderReranker auto→BM25回退) top4，再拼接关键词兜底补齐，阈值 0.2。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    @Autowired(required = false)
    private VectorStore vectorStore;

    private final JdbcTemplate jdbc;
    private final Reranker reranker;
    private final CrossEncoderReranker crossReranker;
    private final MeterRegistry meterRegistry;
    private final RerankMode rerankMode;

    public RagService(JdbcTemplate jdbc, Reranker reranker, CrossEncoderReranker crossReranker, MeterRegistry meterRegistry,
                      @org.springframework.beans.factory.annotation.Value("${smartsupply.rag.rerank-mode:auto}") String rerankModeStr) {
        this.jdbc = jdbc; this.reranker = reranker; this.crossReranker = crossReranker; this.meterRegistry = meterRegistry;
        RerankMode m;
        try { m = RerankMode.valueOf(rerankModeStr.trim().toUpperCase()); } catch (Exception e) { m = RerankMode.AUTO; }
        this.rerankMode = m;
    }

    public String recall(String query) {
        return recallWithDetail(query).context();
    }

    public record RecallDetail(String context, int vectorHits, int keywordHits, int reranked, long latencyMs) {}

    public RecallDetail recallWithDetail(String query) {
        long start = System.currentTimeMillis();
        String q = query == null ? "" : query.trim();
        List<Document> vectorDocs = List.of();
        int vectorHits = 0;
        if (vectorStore != null && !q.isEmpty()) {
            try {
                vectorDocs = vectorStore.similaritySearch(
                        SearchRequest.builder().query(q).topK(8).similarityThreshold(0.2).build());
                vectorHits = vectorDocs == null ? 0 : vectorDocs.size();
                if (vectorDocs != null && !vectorDocs.isEmpty()) {
                    CrossEncoderReranker.RerankResult rr = crossReranker.rerank(q, vectorDocs, 4, rerankMode);
                    vectorDocs = rr.docs();
                    try {
                        io.micrometer.core.instrument.Counter.builder("rag.rerank.count").tag("mode", rr.mode())
                                .register(meterRegistry).increment();
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                log.debug("vector recall failed, degrade to keyword: {}", e.toString());
                vectorDocs = List.of();
            }
        }
        String vectorContext = "";
        if (vectorDocs != null && !vectorDocs.isEmpty()) {
            Timer.builder("rag.recall.latency").tag("mode", "vector").register(meterRegistry)
                    .record(System.currentTimeMillis() - start, java.util.concurrent.TimeUnit.MILLISECONDS);
            vectorContext = vectorDocs.stream().map(Document::getText).collect(Collectors.joining("\n---\n"));
        }

        // 关键词兜底：参数化 ILIKE，防注入；补齐到 3 条以内，避免空召回
        List<Map<String, Object>> keywordRows = List.of();
        if (vectorContext.isBlank()) {
            if (!q.isEmpty()) {
                keywordRows = jdbc.queryForList(
                        "SELECT title, content FROM knowledge_doc WHERE content ILIKE ? LIMIT 3", "%" + q + "%");
            }
            if (keywordRows.isEmpty()) keywordRows = jdbc.queryForList("SELECT title, content FROM knowledge_doc LIMIT 2");
            String kwContext = keywordRows.stream().map(r -> r.get("title") + "：\n" + r.get("content"))
                    .collect(Collectors.joining("\n---\n"));
            long latency = System.currentTimeMillis() - start;
            Timer.builder("rag.recall.latency").tag("mode", "keyword").register(meterRegistry)
                    .record(latency, java.util.concurrent.TimeUnit.MILLISECONDS);
            String ctx = vectorContext.isBlank() ? kwContext : vectorContext + "\n---\n" + kwContext;
            return new RecallDetail(ctx, vectorHits, keywordRows.size(), vectorDocs == null ? 0 : vectorDocs.size(), latency);
        }
        // 向量已命中时，额外补 1 条关键词结果以提升覆盖（混合召回）
        if (!q.isEmpty()) {
            try {
                List<Map<String, Object>> extra = jdbc.queryForList(
                        "SELECT title, content FROM knowledge_doc WHERE content ILIKE ? LIMIT 1", "%" + q + "%");
                if (!extra.isEmpty()) {
                    String extraCtx = extra.get(0).get("title") + "：\n" + extra.get(0).get("content");
                    if (!vectorContext.contains(String.valueOf(extra.get(0).get("content")).substring(0, Math.min(20, String.valueOf(extra.get(0).get("content")).length())))) {
                        vectorContext = vectorContext + "\n---\n" + extraCtx;
                    }
                }
            } catch (Exception ignored) {}
        }
        long latency = System.currentTimeMillis() - start;
        return new RecallDetail(vectorContext, vectorHits, 0, vectorDocs.size(), latency);
    }

    /** 分段入库：knowledge_doc 存原文，knowledge_chunk/vectorStore 存向量 */
    public Long ingest(String title, String content, String sourceType) {
        jdbc.update("INSERT INTO knowledge_doc(title, source_type, content) VALUES (?,?,?)",
                title, sourceType == null ? "UPLOAD" : sourceType, content);
        Long docId = com.smartsupply.common.DbHelper.lastInsertIdByUnique(jdbc, "knowledge_doc", "title", title);
        List<String> chunks = TextSplitter.split(content);
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            jdbc.update("INSERT INTO knowledge_chunk(doc_id, chunk_index, content) VALUES (?,?,?)", docId, i, chunk);
            Document d = new Document(chunk);
            d.getMetadata().put("docId", String.valueOf(docId));
            d.getMetadata().put("title", title);
            docs.add(d);
        }
        if (vectorStore != null && !docs.isEmpty()) {
            try { vectorStore.add(docs); } catch (Exception e) { log.warn("vector add failed docId={}: {}", docId, e.toString()); }
        }
        log.info("RAG ingest docId={} title={} chunks={}", docId, title, chunks.size());
        return docId;
    }

    public Long ingest(String title, String content) { return ingest(title, content, "UPLOAD"); }
}
