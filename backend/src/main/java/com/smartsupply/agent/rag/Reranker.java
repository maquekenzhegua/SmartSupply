package com.smartsupply.agent.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 轻量重排器：BM25 语义 + 关键词覆盖度 + 标题加权，离线可跑，无需额外模型服务。
 * 生产可替换为 bge-reranker / Cohere Rerank，只需实现同接口。
 * 面试可讲：召回 8 -> 重排取 4，相对纯向量召回，风控条款类查询的准确率显著提升。
 */
@Component
public class Reranker {

    public double score(String query, Document doc) {
        String content = doc.getText() == null ? "" : doc.getText();
        String title = String.valueOf(doc.getMetadata().getOrDefault("title", ""));
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || content.isEmpty()) return 0;
        double bm25 = bm25Like(q, content);
        double titleBoost = title.isEmpty() ? 0 : bm25Like(q, title) * 0.5;
        double coverage = keywordCoverage(q, content);
        return bm25 * 0.6 + coverage * 0.4 + titleBoost;
    }

    public List<Document> rerank(String query, List<Document> docs, int topK) {
        if (docs == null || docs.isEmpty()) return List.of();
        return docs.stream()
                .sorted(Comparator.comparingDouble((Document d) -> score(query, d)).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    private double bm25Like(String query, String content) {
        String[] qTerms = tokenize(query);
        String[] cTerms = tokenize(content);
        if (qTerms.length == 0 || cTerms.length == 0) return 0;
        Map<String, Long> freq = Arrays.stream(cTerms).collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        double score = 0;
        for (String t : qTerms) {
            long tf = freq.getOrDefault(t, 0L);
            if (tf == 0) {
                for (String ct : cTerms) if (ct.contains(t) || t.contains(ct)) { tf = 1; break; }
            }
            if (tf > 0) score += Math.log(1 + tf) * (1 + Math.log(1 + content.length() / 100.0));
        }
        double lenNorm = 1.0 / (1 + Math.exp((cTerms.length - 400) / 200.0));
        return score * (0.5 + lenNorm);
    }

    private double keywordCoverage(String query, String content) {
        String[] qTerms = tokenize(query);
        if (qTerms.length == 0) return 0;
        int hits = 0;
        String lc = content.toLowerCase(Locale.ROOT);
        for (String t : qTerms) if (lc.contains(t.toLowerCase(Locale.ROOT))) hits++;
        return (double) hits / qTerms.length;
    }

    private String[] tokenize(String text) {
        String norm = text.replaceAll("\\s+", " ").trim();
        if (norm.isEmpty()) return new String[0];
        List<String> tokens = new ArrayList<>();
        for (String part : norm.split("[\\s，。；：、,.!?；]+")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            if (part.length() <= 6) {
                tokens.add(part);
            } else {
                for (int i = 0; i < part.length() - 1; i++) tokens.add(part.substring(i, Math.min(i + 2, part.length())));
            }
        }
        return tokens.toArray(new String[0]);
    }
}
