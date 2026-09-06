package com.smartsupply.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class VectorStoreConfig {

    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    VectorStore pgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel,
                              @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}") int dims,
                              @Value("${spring.ai.vectorstore.pgvector.initialize-schema:false}") boolean initializeSchema) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(dims)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                // 冷启动时 vector_store 表不存在（Flyway 不管这张表），由 Spring AI 建表；
                // 不设则所有 add() 以 BadSqlGrammarException 静默失败，向量检索整体退化为 ILIKE。
                // 建表首条语句是 PG 专有的 CREATE EXTENSION vector，H2 无法解析，
                // 因此仅 PG 运行态（prod/vmware profile）置 true，默认/测试保持 false。
                .initializeSchema(initializeSchema)
                .build();
    }
}
