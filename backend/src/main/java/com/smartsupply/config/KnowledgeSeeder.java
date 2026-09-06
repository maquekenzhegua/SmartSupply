package com.smartsupply.config;

import com.smartsupply.agent.rag.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 知识库冷启动：随仓 classpath:knowledge/*.md 启动时走真实 ingestion
 * （分段 → knowledge_doc/knowledge_chunk → 向量库），解决"库内无文档可召回"的问题。
 * 按标题去重可重复执行；生产 PG 与离线 H2 演示同一路径。
 */
@Configuration
public class KnowledgeSeeder {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSeeder.class);

    private final Environment env;

    public KnowledgeSeeder(Environment env) { this.env = env; }

    @Bean
    @Order(2) // 在 DemoDataInitializer(@Order(1)) 建表之后执行
    ApplicationRunner seedKnowledgeDocs(RagService ragService, JdbcTemplate jdbc) {
        return args -> {
            if (Arrays.asList(env.getActiveProfiles()).contains("test")) return;
            try {
                jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_doc", Long.class);
            } catch (Exception e) {
                log.warn("KnowledgeSeeder: knowledge_doc 不可用，跳过种子入库");
                return;
            }
            int ingested = 0;
            try {
                Resource[] resources = new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:knowledge/*.md");
                for (Resource res : resources) {
                    String content = StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
                    String title = titleOf(content, res.getFilename());
                    Long seeded = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM knowledge_doc d WHERE d.title = ? "
                                    + "AND EXISTS (SELECT 1 FROM knowledge_chunk c WHERE c.doc_id = d.id)",
                            Long.class, title);
                    if (seeded != null && seeded > 0) continue;
                    // 自愈：清理历史上只有 doc 行、没有 chunk/向量的孤儿记录后重建
                    jdbc.update("DELETE FROM knowledge_doc WHERE title = ?", title);
                    ragService.ingest(title, content, "SEED");
                    ingested++;
                }
            } catch (Exception e) {
                log.warn("KnowledgeSeeder 执行失败（不影响启动）: {}", e.toString());
            }
            log.info("KnowledgeSeeder: seeded {} knowledge docs from classpath:knowledge/", ingested);
        };
    }

    private String titleOf(String content, String fallback) {
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith("# ")) return t.substring(2).trim();
        }
        return fallback == null ? "内置知识文档" : fallback;
    }
}
