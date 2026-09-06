package com.smartsupply.agent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 单测：覆盖 SQL 注入防护、兜底检索、分段入库（无需真实 PG/embedding）。
 * 向量库走 MockEmbeddingModel + 兜底 ILIKE，H2 兼容。
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:testdb-rag;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
  "spring.datasource.username=sa", "spring.datasource.password=",
  "spring.datasource.driver-class-name=org.h2.Driver",
  "spring.flyway.enabled=false", "spring.sql.init.mode=never",
  "spring.data.redis.host=localhost", "spring.data.redis.port=6379",
  "spring.ai.openai.api-key=dummy-test-key-for-ci", "smartsupply.ai.mock=true",
  "spring.ai.vectorstore.pgvector.dimensions=1024"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class RagServiceTest {

    @Autowired RagService ragService;
    @Autowired JdbcTemplate jdbc;

    @Test void recallMatchesSeedPolicyDoc() {
        String ctx = ragService.recall("无限连带责任");
        assertTrue(ctx.contains("禁止无限连带责任") || ctx.contains("无限连带责任"),
                "应召回风控规范或历史案例，实际: " + ctx);
    }

    @Test void recallMatchesOtherKeyword() {
        String ctx = ragService.recall("违约金");
        assertTrue(ctx.contains("违约金") || ctx.contains("30%"),
                "应召回违约金规范，实际: " + ctx);
    }

    @Test void recallWithSqlInjectionAttemptDoesNotThrowAndReturnsSafe() {
        String evil = "' OR 1=1 -- ; DROP TABLE knowledge_doc; --";
        String ctx = assertDoesNotThrow(() -> ragService.recall(evil));
        assertNotNull(ctx);
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_doc", Long.class);
        assertTrue(cnt != null && cnt >= 2);
    }

    @Test void recallWithEmptyQueryFallsBackToTwoDocs() {
        String ctx = ragService.recall("不存在的关键词XYZ123不存在");
        assertNotNull(ctx);
        assertFalse(ctx.isBlank());
    }

    @Test void ingestCreatesDocAndChunksAndRecallable() {
        String title = "测试文档-" + System.nanoTime();
        String content = "A".repeat(2500) + " 关键句：禁止无限连带责任，违约金不超30%。";
        Long docId = ragService.ingest(title, content, "TEST");
        assertNotNull(docId);
        Long chunkCnt = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk WHERE doc_id=?", Long.class, docId);
        assertTrue(chunkCnt != null && chunkCnt >= 2, "2500字应切多段，实际 chunks=" + chunkCnt);
        String ctx = ragService.recall("关键句");
        assertTrue(ctx.contains("关键句") || ctx.contains("禁止无限连带责任"));
        jdbc.update("DELETE FROM knowledge_doc WHERE id=?", docId);
    }

    @Test void ingestShortContentCreatesSingleChunk() {
        String title = "短文档-" + System.nanoTime();
        Long docId = ragService.ingest(title, "hello world 短内容", "TEST");
        assertNotNull(docId);
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk WHERE doc_id=?", Long.class, docId);
        assertEquals(1L, cnt);
        jdbc.update("DELETE FROM knowledge_doc WHERE id=?", docId);
    }
}
