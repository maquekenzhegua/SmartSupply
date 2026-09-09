package com.smartsupply.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真 PG 回归（可证明成熟的关键一环）：H2(PostgreSQL 模式) 与真 PG 永远存在方言/迁移漂移——
 * created_by 类型 bug 在 H2 上 68 用例全绿、真库必炸就是活证据。本类用 Testcontainers 起
 * pgvector/pgvector:pg16，走真实 Flyway 迁移（V1→V4）+ 真实 PG SQL 验证核心写链路。
 *
 * 与 H2 测试的差异：不挂 schema-h2.sql（@Sql），schema 全部由应用内 Flyway 自建——
 * 迁移脚本本身从此进入回归范围。无 Docker 环境（disabledWithoutDocker）自动跳过，
 * CI 的 ubuntu runner 带 Docker，会真实执行。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        // 与 H2 测试同口径：mock 模型/向量，零外部网络；Redis 不存在时限流/幂等走诚实降级
        "spring.ai.openai.api-key=dummy", "smartsupply.ai.mock=true",
        "spring.data.redis.host=localhost", "spring.data.redis.port=6379"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostgresRegressionTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("smartsupply").withUsername("dev").withPassword("dev");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        // 真 PG 上让 PgVectorStore 自建 vector_store（pgvector 镜像含 extension），
        // 覆盖 application-test.yml 里针对 H2 的 false——顺带把"向量层真建表"纳入回归
        r.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> "true");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate stringRedis;

    @org.junit.jupiter.api.BeforeEach
    void flushPurchaseIdempotencyKeys() {
        // 测试隔离：PG 容器每次全新，但 Redis（localhost）跨运行共享——agentWrite 的固定
        // payload 在 10 分钟 TTL 内会命中上次运行留下的幂等键，返回 success=false
        try {
            var keys = stringRedis.keys("idem:po:*");
            if (keys != null && !keys.isEmpty()) stringRedis.delete(keys);
        } catch (Exception ignored) {
            // Redis 不可用时幂等服务本身走诚实降级，不阻塞回归
        }
    }

    private String token() throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String tokenBody() throws Exception {
        return om.readTree(token()).path("data").path("token").asText();
    }

    @Test
    void flywayMigrationsLandRealColumnsOnPg() throws Exception {
        // 迁移真实性：V4 的审批留痕列在真 PG 上存在（H2 回归测不到迁移本身）
        Integer cols = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns " +
                        "WHERE table_name='purchase_order' AND column_name IN ('approver','approved_at')",
                Integer.class);
        assertThat(cols).isEqualTo(2);
        // 演示账号由 DemoUserInitializer 以真实 BCrypt 落库（V1 种的是占位哈希）
        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM sys_user WHERE username='admin'", String.class);
        assertThat(hash).doesNotContain("REPLACE_ON_STARTUP");
    }

    @Test
    void agentWritePersistsWithCreatorIdOnRealPg() throws Exception {
        // 实跑事故回归：created_by(BIGINT) 曾被塞用户名字符串，真 PG 必炸而 H2 全绿
        String t = tokenBody();
        String body = om.writeValueAsString(Map.of(
                "supplierId", 1, "skuCode", "SKU-T001-WH-M", "quantity", 42, "unitPrice", 9.9));
        String res = mvc.perform(post("/api/agent/purchase-orders")
                        .header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andReturn().getResponse().getContentAsString();
        String orderNo = om.readTree(res).path("data").path("orderNo").asText();
        Long adminId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
        Long creator = jdbc.queryForObject(
                "SELECT created_by FROM purchase_order WHERE order_no=?", Long.class, orderNo);
        assertThat(creator).isEqualTo(adminId);
        // 明细行同事务落库
        Integer items = jdbc.queryForObject(
                "SELECT count(*) FROM purchase_order_item WHERE order_id=" +
                        "(SELECT id FROM purchase_order WHERE order_no=?)", Integer.class, orderNo);
        assertThat(items).isEqualTo(1);
    }

    @Test
    void poLifecycleCasAndAutoInboundOnRealPg() throws Exception {
        String t = tokenBody();
        String createBody = om.writeValueAsString(Map.of(
                "supplierId", 1, "remark", "PgIT 采购单",
                "items", java.util.List.of(Map.of("skuId", 1, "quantity", 5, "unitPrice", 28.5))));
        String cr = mvc.perform(post("/api/purchase-orders-extra")
                        .header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        long poId = om.readTree(cr).path("data").path("id").asLong();
        Integer qtyBefore = jdbc.queryForObject(
                "SELECT quantity FROM inventory WHERE sku_id=1 AND warehouse_id=1", Integer.class);

        // CAS：重复 APPROVED（前驱状态已不在）必须 409/400 拦下，真 PG 行级语义
        mvc.perform(put("/api/purchase-orders-extra/" + poId + "/status")
                        .header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("status", "APPROVED"))))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(put("/api/purchase-orders-extra/" + poId + "/status")
                        .header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("status", "APPROVED"))))
                .andExpect(jsonPath("$.code").value(400));

        Map<String, Object> po = jdbc.queryForMap(
                "SELECT status, approver, approved_at FROM purchase_order WHERE id=?", poId);
        assertThat(po.get("status")).isEqualTo("APPROVED");
        assertThat(po.get("approver")).isEqualTo("admin");
        assertThat(po.get("approved_at")).isNotNull();

        // RECEIVED 自动入库 + 流水（真 PG 事务内完成）
        mvc.perform(put("/api/purchase-orders-extra/" + poId + "/status")
                        .header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("status", "RECEIVED"))))
                .andExpect(jsonPath("$.code").value(200));
        Integer qtyAfter = jdbc.queryForObject(
                "SELECT quantity FROM inventory WHERE sku_id=1 AND warehouse_id=1", Integer.class);
        assertThat(qtyAfter).isEqualTo(qtyBefore + 5);
        Integer flows = jdbc.queryForObject(
                "SELECT count(*) FROM inventory_flow WHERE reason LIKE '采购入库 %'", Integer.class);
        assertThat(flows).isGreaterThanOrEqualTo(1);
    }

    @Test
    void concurrentAdjustCannotOversellOnRealPg() throws Exception {
        // 真 PG 行锁回归：两个并发调整各扣超过一半库存，旧的"读-改-写绝对值覆盖"实现下
        // 两个都会成功且互相覆盖；原子增量 UPDATE（WHERE quantity+? >= 0）必须恰好一个成功。
        String t = tokenBody();
        Integer base = jdbc.queryForObject(
                "SELECT quantity FROM inventory WHERE sku_id=1 AND warehouse_id=1", Integer.class);
        int take = base / 2 + 1;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    // 业务错误码在响应体（Result.fail 为 HTTP 200 + body code），必须解析 body
                    String body = mvc.perform(post("/api/inventory/adjust")
                                    .header("Authorization", "Bearer " + t)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(om.writeValueAsString(Map.of(
                                            "skuId", 1, "warehouseId", 1, "changeQty", -take))))
                            .andReturn().getResponse().getContentAsString();
                    return om.readTree(body).path("code").asInt();
                }));
            }
            int ok = 0, rejected = 0;
            for (var f : futures) {
                int code = f.get(30, java.util.concurrent.TimeUnit.SECONDS);
                if (code == 200) ok++; else rejected++;
            }
            assertThat(ok).as("并发双扣必须恰好一个成功").isEqualTo(1);
            assertThat(rejected).as("另一个必须被非负守卫拒绝").isEqualTo(1);
            Integer after = jdbc.queryForObject(
                    "SELECT quantity FROM inventory WHERE sku_id=1 AND warehouse_id=1", Integer.class);
            assertThat(after).isEqualTo(base - take);
        } finally {
            pool.shutdownNow();
        }
    }
}
