package com.smartsupply.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 离线演示兜底：当使用 H2 且 Flyway 未建表时，自动执行 schema-h2-demo.sql。
 * 生产/VMware 的 PG 走 Flyway，不会触发；测试环境由 @Sql 初始化，跳过以免重复插 admin。
 */
@Configuration
public class DemoDataInitializer {

    @Value("${spring.flyway.enabled:true}")
    private boolean flywayEnabled;

    private final Environment env;

    public DemoDataInitializer(Environment env) { this.env = env; }

    @Bean
    @org.springframework.core.annotation.Order(1) // KnowledgeSeeder(@Order(2)) 依赖本 runner 先建表
    ApplicationRunner initH2SchemaIfNeeded(JdbcTemplate jdbc) {
        return args -> {
            if (flywayEnabled) return;
            if (Arrays.asList(env.getActiveProfiles()).contains("test")) return;
            try {
                jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_doc", Long.class);
                return;
            } catch (Exception ignore) {
                // 表不存在 -> 继续初始化
            }
            try {
                ClassPathResource res = new ClassPathResource("schema-h2-demo.sql");
                if (!res.exists()) res = new ClassPathResource("db/migration/V1__init.sql");
                String raw = StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
                // 去掉注释行和 CREATE EXTENSION，保留建表语句
                StringBuilder cleaned = new StringBuilder();
                for (String line : raw.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("--") || t.startsWith("CREATE EXTENSION")) continue;
                    cleaned.append(line).append("\n");
                }
                String sql = cleaned.toString();
                for (String stmt : sql.split(";")) {
                    String s = stmt.trim();
                    if (s.isEmpty()) continue;
                    try { jdbc.execute(s); } catch (Exception ex) {
                        System.err.println("[DemoDataInitializer] stmt failed: " + ex.getMessage() + " | " + s.substring(0, Math.min(120, s.length())));
                    }
                }
                Long cnt = null;
                try { cnt = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_doc", Long.class); } catch (Exception ignore2) {}
                System.out.println("[DemoDataInitializer] H2 schema initialized for offline demo, knowledge_doc=" + cnt);
            } catch (Exception ex) {
                System.err.println("[DemoDataInitializer] failed: " + ex.getMessage());
                ex.printStackTrace();
            }
        };
    }
}
