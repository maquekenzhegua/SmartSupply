package com.smartsupply.config;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Embedding 验证（默认跳过，不进 CI）：走与生产一致的 OpenAiEmbeddingModel 代码路径。
 * 跑法：EVAL_REAL_LLM=1 EMBEDDING_BASE_URL=http://localhost:11434/v1 EMBEDDING_API_KEY=ollama \
 *        EMBEDDING_MODEL=qwen3-embedding:0.6b VECTOR_DIMENSIONS=1024 \
 *        mvn -B test -Dtest=OllamaEmbeddingRealTest
 */
class OllamaEmbeddingRealTest {

    private EmbeddingModel model() {
        String base = System.getenv().getOrDefault("EMBEDDING_BASE_URL", "http://localhost:11434/v1");
        String key = System.getenv().getOrDefault("EMBEDDING_API_KEY", "ollama");
        String model = System.getenv().getOrDefault("EMBEDDING_MODEL", "qwen3-embedding:0.6b");
        int dims = Integer.parseInt(System.getenv().getOrDefault("VECTOR_DIMENSIONS", "1024"));
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(key).baseUrl(base);
        if (base.endsWith("/v1") || base.endsWith("/v4") || base.endsWith("/compatible-mode")) {
            apiBuilder.embeddingsPath("/embeddings");
        }
        return new OpenAiEmbeddingModel(apiBuilder.build(), MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(model).build());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EVAL_REAL_LLM", matches = "true|1|yes")
    void realEmbeddingMatchesConfiguredDimensions() {
        float[] vec = model().embed(new org.springframework.ai.document.Document("禁止无限连带责任条款，违约金不得超过合同额30%"));
        assertNotNull(vec);
        int expected = Integer.parseInt(System.getenv().getOrDefault("VECTOR_DIMENSIONS", "1024"));
        assertEquals(expected, vec.length, "向量维度须与 VECTOR_DIMENSIONS/建表列宽一致，否则 pgvector 入库失败");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EVAL_REAL_LLM", matches = "true|1|yes")
    void chineseSemanticDiscrimination() {
        EmbeddingModel m = model();
        float[] contractRisk = m.embed(new org.springframework.ai.document.Document("禁止无限连带责任条款，违约金不得超过合同额30%"));
        float[] contractRelated = m.embed(new org.springframework.ai.document.Document("合同条款存在重大合规风险，建议修改违约责任"));
        float[] unrelated = m.embed(new org.springframework.ai.document.Document("今天天气很好，适合出去爬山"));
        double related = cos(contractRisk, contractRelated);
        double noise = cos(contractRisk, unrelated);
        System.out.printf("[RealEmbedding] related=%.4f unrelated=%.4f gap=%.4f%n", related, noise, related - noise);
        // 中文语义检索的最低要求：相关对相似度显著高于无关对
        assertTrue(related - noise > 0.05, "语义区分度不足(gap=" + (related - noise) + ")，该模型不适合做本项目中文 RAG");
    }

    private static double cos(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
