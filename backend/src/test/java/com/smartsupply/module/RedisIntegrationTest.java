package com.smartsupply.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 真 Redis 集成回归（Testcontainers）：限流与幂等此前只测过"Redis 不可用降级"分支，
 * 真实 Redis 行为（Lua INCR 原子性、并发配额、幂等键互斥）无任何集成覆盖。
 * 本类用真 redis:7 容器跑通两条关键路径；无 Docker 环境自动跳过（CI runner 带 Docker 会执行）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-redis;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false", "spring.sql.init.mode=never",
        "spring.ai.openai.api-key=dummy", "smartsupply.ai.mock=true",
        // 测试全局 yml 关了限流（确定性考虑）；本类要回归限流，显式打开
        "smartsupply.ratelimit.enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.jdbc.Sql(scripts = "/schema-h2.sql",
        executionPhase = org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class RedisIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired StringRedisTemplate redis;

    private String adminToken;

    @BeforeEach
    void setup() throws Exception {
        // 真 Redis 在多个测试类/多次运行间共享状态（本机 6379 与容器隔离，容器内干净，
        // 但限流/幂等键仍需按用例清理保证可重复）
        var keys = redis.keys("rl:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        var idem = redis.keys("idem:po:*");
        if (idem != null && !idem.isEmpty()) redis.delete(idem);
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin123"))))
                .andReturn().getResponse().getContentAsString();
        adminToken = om.readTree(body).path("data").path("token").asText();
    }

    @Test
    void rateLimitEnforcesPerMinuteQuotaWith429() throws Exception {
        // agent-chat 30/min（key=agent-chat + 客户端 IP）；前 30 次放行，第 31 次 429
        int ok = 0, limited = 0, other = 0;
        for (int i = 0; i < 31; i++) {
            String res = mvc.perform(post("/api/agent/chat")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of("message", "你好", "sessionId", "rl"))))
                    .andReturn().getResponse().getContentAsString();
            int code = om.readTree(res).path("code").asInt(200);
            if (code == 200) ok++;
            else if (code == 429) limited++;
            else other++;
        }
        assertThat(other).as("不得出现 429/200 之外的响应（鉴权或系统错误）").isZero();
        assertThat(ok).isEqualTo(30);
        assertThat(limited).isEqualTo(1);
        // 配额键确实落在 Redis（Lua INCR+PEXPIRE 原子写入），1 分钟窗口内持续拦截
        assertThat(redis.keys("rl:*")).isNotEmpty();
    }

    @Test
    void concurrentDuplicateCreateIsExactlyOneSuccess() throws Exception {
        // 幂等键并发互斥：两个线程同 payload 同时创建采购单，恰好一个 success=true，
        // 另一个 duplicate=true（tryAcquire 在真 Redis 上必须不可重入）
        String payload = om.writeValueAsString(Map.of(
                "supplierId", 1, "skuCode", "SKU-T001-WH-M", "quantity", 7, "unitPrice", 9.9));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var futures = java.util.List.of(
                    pool.submit(() -> mvc.perform(post("/api/agent/purchase-orders")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON).content(payload))
                            .andReturn().getResponse().getContentAsString()),
                    pool.submit(() -> mvc.perform(post("/api/agent/purchase-orders")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON).content(payload))
                            .andReturn().getResponse().getContentAsString()));
            int success = 0, duplicate = 0;
            for (Future<String> f : futures) {
                var data = om.readTree(f.get(30, java.util.concurrent.TimeUnit.SECONDS)).path("data");
                if (data.path("success").asBoolean(false)) success++;
                else if (data.path("duplicate").asBoolean(false)) duplicate++;
            }
            assertThat(success).as("并发双创建恰好一个成功").isEqualTo(1);
            assertThat(duplicate).as("另一个被幂等键拦截").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
