package com.smartsupply.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST 写接口 RBAC 回归：写操作统一 ADMIN（与 Agent 写工具 ToolSecurity 同口径）。
 * 守护点：GlobalExceptionHandler 曾误导入 java.nio.file.AccessDeniedException，
 * @PreAuthorize 拒绝落入兜底 500 而非 403——本类若见 500 即失败，防止该回归复发。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-rbac;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false", "spring.sql.init.mode=never",
        "spring.data.redis.host=localhost", "spring.data.redis.port=6379",
        "spring.ai.openai.api-key=dummy", "smartsupply.ai.mock=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Sql(scripts = "/schema-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class SecurityRbacTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

    @org.junit.jupiter.api.BeforeEach
    void seedOpsUser() {
        // schema-h2.sql 只种了 admin（真实哈希）；DemoUserInitializer 在上下文启动时先于
        // @Sql 执行会因表不存在而跳过，这里显式补种 ops（RBAC 拒绝路径需要非 ADMIN 账号）
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username='ops'", Integer.class);
        if (cnt == null || cnt == 0) {
            jdbc.update("INSERT INTO sys_user(username, password_hash, nickname, role) VALUES ('ops',?, '运营专员', 'OPS')",
                    encoder.encode("ops123"));
        }
    }

    private String token(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).path("data").path("token").asText();
    }

    @Test
    void opsCanReadButAllWritesAreDeniedWith403() throws Exception {
        String t = token("ops", "ops123");
        // 只读角色：读接口正常
        mvc.perform(get("/api/suppliers").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk());

        // 主数据/库存/知识库/采购的全部写路径：必须 403（而非 500 或静默成功）
        mvc.perform(delete("/api/suppliers-extra/1").header("Authorization", "Bearer " + t))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/products").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "越权商品"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(delete("/api/skus/1").header("Authorization", "Bearer " + t))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/warehouses/1").header("Authorization", "Bearer " + t))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/contracts-extra/1").header("Authorization", "Bearer " + t))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/knowledge/1").header("Authorization", "Bearer " + t))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/adjust").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("skuId", 1, "warehouseId", 1, "changeQty", -1))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/purchase-orders-extra").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("supplierId", 1, "items",
                                java.util.List.of(Map.of("skuId", 1, "quantity", 1, "unitPrice", 1.0))))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/purchase-orders-extra/1/status").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("status", "APPROVED"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanStillWriteAndGeneratedKeyReturnsRealId() throws Exception {
        String t = token("admin", "admin123");
        // 顺带回归 DbHelper.insertAndReturnId：创建必须返回真实自增 id（此前按无 UNIQUE 约束的
        // name 反查，并发同名会挂错行）
        String res = mvc.perform(post("/api/products").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "RBAC回归商品", "category", "测试", "unit", "个"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = om.readTree(res).path("data").path("id").asLong();
        org.assertj.core.api.Assertions.assertThat(id).isPositive();
        // 详情断言走 jdbc（H2 PG 模式 SELECT * 列名转大写，$.data.name 断言不可移植）
        String name = jdbc.queryForObject("SELECT name FROM product WHERE id=?", String.class, id);
        org.assertj.core.api.Assertions.assertThat(name).isEqualTo("RBAC回归商品");
    }

    @Test
    void invalidTokenIs401SoFrontendCanRelogin() throws Exception {
        // JwtAuthFilter 修复回归：带了 token 但解析失败（过期/篡改）必须显式 401
        //（此前静默降级为匿名 → 403，前端 401→跳登录 链路永不触发）
        mvc.perform(get("/api/suppliers").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        // 完全不带 token（匿名）保持既有契约：Spring Security 默认 403（见 AuthControllerTest）
        mvc.perform(get("/api/suppliers"))
                .andExpect(status().isForbidden());
    }
}
