package com.smartsupply.agent.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * 轻量重排器：BM25（k1/b 词频饱和 + 长度归一 + 候选集局部 IDF）+ 关键词覆盖度 + 标题加权，
 * 离线可跑，无需额外模型服务。
 * <p>
 * 诚实口径：IDF 在"本次召回的候选集"上统计（局部 IDF），不是全库语料统计——top-8 的
 * 候选集内做判别是够用的，但不等于语料级 BM25；生产要更强判别力就换 cross-encoder
 * （见 CrossEncoderReranker，同一接口）。中文分词：短词直用，长句滑双字（bigram）。
 */
@Component
public class Reranker {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final double W_BM25 = 0.6;
    private static final double W_COVERAGE = 0.4;
    private static final double TITLE_BOOST = 0.5;

    public List<Document> rerank(String query, List<Document> docs, int topK) {
        if (docs == null || docs.isEmpty()) return List.of();
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return docs.stream().limit(topK).collect(Collectors.toList());

        int n = docs.size();
        List<String[]> docTerms = new ArrayList<>(n);
        for (Document d : docs) {
            String text = d.getText() == null ? "" : d.getText();
            docTerms.add(tokenize(text));
        }
        double avgLen = docTerms.stream().mapToInt(a -> a.length).average().orElse(0);
        // 局部 IDF：df = 含该词的候选文档数（仅在本批候选上统计，见类注释诚实口径）
        Map<String, Long> df = new HashMap<>();
        for (String[] terms : docTerms) {
            Set<String> distinct = new HashSet<>(Arrays.asList(terms));
            for (String t : distinct) df.merge(t, 1L, Long::sum);
        }

        record Scored(Document doc, double score) {}
        List<Scored> scored = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Document doc = docs.get(i);
            String content = doc.getText() == null ? "" : doc.getText();
            String[] terms = docTerms.get(i);
            String title = String.valueOf(doc.getMetadata().getOrDefault("title", ""));
            double bm25 = bm25(q, terms, avgLen, df, n);
            double titleBm25 = title.isEmpty() ? 0 : bm25(q, tokenize(title), 0, df, n);
            double coverage = keywordCoverage(q, content);
            scored.add(new Scored(doc, W_BM25 * bm25 + W_COVERAGE * coverage + TITLE_BOOST * titleBm25));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .map(Scored::doc)
                .collect(Collectors.toList());
    }

    /**
     * BM25：idf = ln(1 + (N - df + 0.5) / (df + 0.5))（df=N 时不为负）；
     * tf 饱和 tf*(k1+1)/(tf + k1*(1-b+b*len/avgLen))。avgLen<=0 的退化场景（标题）
     * 跳过长度归一，只保留词频与 IDF。
     */
    private double bm25(String query, String[] docTerms, double avgLen, Map<String, Long> df, int n) {
        String[] qTerms = tokenize(query);
        if (qTerms.length == 0 || docTerms.length == 0) return 0;
        Map<String, Long> tf = Arrays.stream(docTerms).collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        double score = 0;
        for (String t : qTerms) {
            long f = tf.getOrDefault(t, 0L);
            if (f == 0) continue; // 词形不匹配：中文 bigram 已在 tokenize 层对齐，不做 contains 模糊
            double d = df.getOrDefault(t, 0L);
            double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
            double norm = avgLen > 0 ? f * (K1 + 1) / (f + K1 * (1 - B + B * docTerms.length / avgLen)) : f * (K1 + 1) / (f + K1);
            score += idf * norm;
        }
        return score;
    }

    private double keywordCoverage(String query, String content) {
        String[] qTerms = tokenize(query);
        if (qTerms.length == 0) return 0;
        int hits = 0;
        String lc = content.toLowerCase(Locale.ROOT);
        for (String t : qTerms) if (lc.contains(t.toLowerCase(Locale.ROOT))) hits++;
        return (double) hits / qTerms.length;
    }

    /** CJK 段统一滑双字（查询与文档进同一 token 空间，长度>2 即 bigram），非 CJK 段整词小写保留。 */
    private String[] tokenize(String text) {
        String norm = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (norm.isEmpty()) return new String[0];
        List<String> tokens = new ArrayList<>();
        for (String part : norm.split("[\\s，。；：、,.!?；]+")) {
            if (part.isEmpty()) continue;
            Matcher m = CJK_RUN.matcher(part);
            while (m.find()) {
                String seg = m.group();
                if (!seg.matches("[\\u4e00-\\u9fff]+")) {
                    tokens.add(seg.toLowerCase(Locale.ROOT));
                    continue;
                }
                if (seg.length() <= 2) {
                    tokens.add(seg);
                    continue;
                }
                for (int i = 0; i < seg.length() - 1; i++) tokens.add(seg.substring(i, i + 2));
            }
        }
        return tokens.toArray(new String[0]);
    }

    private static final java.util.regex.Pattern CJK_RUN =
            java.util.regex.Pattern.compile("[\\u4e00-\\u9fff]+|[^\\u4e00-\\u9fff]+");
}
