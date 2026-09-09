package com.smartsupply.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 库存守卫回归：调整必须"增量 + 非负守卫"原子完成，绝不允许扣成负数。
 * （并发竞态的回归在 PostgresRegressionTest 上用真 PG 行锁验证，H2 只验证守卫语义。）
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-inv;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
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
class InventoryGuardTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;

    private String token() throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin123"))))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).path("data").path("token").asText();
    }

    private int adjust(String token, long skuId, long whId, int changeQty) throws Exception {
        String res = mvc.perform(post("/api/inventory/adjust").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("skuId", skuId, "warehouseId", whId, "changeQty", changeQty))))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(res).path("code").asInt();
    }

    @Test
    void overdrawIsRejectedAndQuantityUntouched() throws Exception {
        String t = token();
        Integer before = jdbc.queryForObject(
                "SELECT quantity FROM inventory WHERE sku_id=1 AND warehouse_id=1", Integer.class);
        int code = adjust(t, 1, 1, -(before + 1));
        assertThat(code).isEqualTo(400);
        Integer after = jdbc.queryForObject(
                "SELECT quantity FROM inventory WHERE sku_id=1 AND warehouse_id=1", Integer.class);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void firstAdjustCreatesRowThenGuardApplies() throws Exception {
        String t = token();
        // 找一个尚无库存行的 (sku, warehouse) 组合：验证"无行新建 + 新行同样受非负守卫"
        Map<String, Object> combo = jdbc.queryForMap("""
                SELECT s.id AS "sku_id", w.id AS "wh_id" FROM sku s
                JOIN warehouse w ON 1=1
                WHERE NOT EXISTS (SELECT 1 FROM inventory i
                    WHERE i.sku_id=s.id AND i.warehouse_id=w.id)
                ORDER BY s.id, w.id LIMIT 1""");
        long skuId = ((Number) combo.get("sku_id")).longValue();
        long whId = ((Number) combo.get("wh_id")).longValue();

        assertThat(adjust(t, skuId, whId, -1)).isEqualTo(400);      // 无行不能扣减
        assertThat(adjust(t, skuId, whId, 5)).isEqualTo(200);        // 新建入库 5
        Integer qty = jdbc.queryForObject(
                "SELECT quantity FROM inventory WHERE sku_id=? AND warehouse_id=?", Integer.class, skuId, whId);
        assertThat(qty).isEqualTo(5);
        assertThat(adjust(t, skuId, whId, -6)).isEqualTo(400);       // 守卫：扣到 -1 拒绝
        assertThat(adjust(t, skuId, whId, -5)).isEqualTo(200);       // 恰好清零允许
        qty = jdbc.queryForObject(
                "SELECT quantity FROM inventory WHERE sku_id=? AND warehouse_id=?", Integer.class, skuId, whId);
        assertThat(qty).isEqualTo(0);
    }
}
