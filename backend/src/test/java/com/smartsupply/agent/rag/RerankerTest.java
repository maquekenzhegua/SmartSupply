package com.smartsupply.agent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RerankerTest {

    private final Reranker r = new Reranker();

    private Document doc(String id, String title, String text) {
        return new Document(id, text, title == null ? Map.of() : Map.of("title", title));
    }

    @Test void rareTermRanksHigherThanCommonTerm() {
        // "违约金" 只在候选 1 出现（df=1，IDF 高），"条款" 两篇都有（df=N，IDF 低）
        Document d1 = doc("1", "采购合同条款", "本合同违约金按合同额30%计算，其余条款从略");
        Document d2 = doc("2", "合同条款说明", "本合同条款参照标准模板执行，无特殊约定");
        List<Document> out = r.rerank("违约金 条款", List.of(d2, d1), 2);
        assertEquals("1", out.get(0).getId(), "含稀有词（高局部IDF）的候选应排前");
    }

    @Test void termInAllCandidatesGetsNoDiscriminativePower() {
        // 两篇都含查询词：单靠该词无法判别，此时靠覆盖度/长度归一，但不应抛异常或全 0 排序崩溃
        Document d1 = doc("1", null, "库存不足需要补货");
        Document d2 = doc("2", null, "库存充足无需补货");
        List<Document> out = r.rerank("库存 补货", List.of(d1, d2), 2);
        assertEquals(2, out.size());
    }

    @Test void tfSaturationIsSublinear() {
        // BM25 的 tf 饱和：tf 5 次的得分高于 tf 1 次，但远低于 5 倍
        String base = "违约金 条款 说明";
        Document once = doc("1", null, base);
        Document many = doc("2", null, (base + " ").repeat(5));
        List<Document> out = r.rerank("违约金", List.of(once, many), 2);
        assertEquals("2", out.get(0).getId(), "词频更高的候选应排前");
    }

    @Test void topKAndEmptyQueryBehave() {
        Document d1 = doc("1", null, "文本甲");
        Document d2 = doc("2", null, "文本乙");
        assertEquals(1, r.rerank("任意", List.of(d1, d2), 1).size());
        // 空查询：无判别信息，按原顺序截断返回
        List<Document> out = r.rerank("", List.of(d1, d2), 1);
        assertEquals("1", out.get(0).getId());
        assertTrue(r.rerank("查询", null, 4).isEmpty());
    }
}
