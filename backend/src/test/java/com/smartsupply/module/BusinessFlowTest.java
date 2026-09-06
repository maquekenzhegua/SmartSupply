package com.smartsupply.module;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 业务全链路回归：库存/采购/合同/BI/知识库，STAR 中的 Task/Action 可量化依据。
 * 覆盖面试常问的“业务闭环是否可测、是否真写库、是否可回放”。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-biz;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
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
class BusinessFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate stringRedis;

    private String token() throws Exception {
        return token("admin", "admin123");
    }

    private String token(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).path("data").path("token").asText();
    }

    // ========== 库存域 ==========

    @Test void inventoryListAndLowStockConsistent() throws Exception {
        String t = token();
        String listJson = mvc.perform(get("/api/inventory").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int total = om.readTree(listJson).path("data").path("total").asInt();
        assertThat(total).isGreaterThanOrEqualTo(4);

        String lowJson = mvc.perform(get("/api/inventory/low-stock").header("Authorization", "Bearer " + t))
                .andReturn().getResponse().getContentAsString();
        int lowCnt = om.readTree(lowJson).path("data").size();
        assertThat(lowCnt).isEqualTo(2); // 种子数据 120/200 与 45/100 低于安全库存
    }

    @Test void inventoryAdjustAndFlowsRecorded() throws Exception {
        String t = token("admin", "admin123");
        Integer before = jdbc.queryForObject(
                "SELECT quantity FROM inventory WHERE sku_id=1 AND warehouse_id=1", Integer.class);
        // SKU 1 在仓库1加 10 -> 差值断言（其它用例可能已变更共享库存，硬编码数值会互踩）
        mvc.perform(post("/api/inventory/adjust").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("skuId", 1, "warehouseId", 1, "changeQty", 10, "reason", "TEST_BIZ"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        Integer after = jdbc.queryForObject(
                "SELECT quantity FROM inventory WHERE sku_id=1 AND warehouse_id=1", Integer.class);
        assertThat(after).isEqualTo(before + 10);

        // 流水可查
        String flows = mvc.perform(get("/api/inventory/flows").header("Authorization", "Bearer " + t))
                .andReturn().getResponse().getContentAsString();
        assertThat(om.readTree(flows).path("data").path("total").asInt()).isGreaterThanOrEqualTo(1);

        // 恢复
        mvc.perform(post("/api/inventory/adjust").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("skuId", 1, "warehouseId", 1, "changeQty", -10, "reason", "TEST_ROLLBACK"))))
                .andExpect(status().isOk());

        // 边界：扣至负数应 400
        mvc.perform(post("/api/inventory/adjust").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("skuId", 3, "warehouseId", 2, "changeQty", -9999, "reason", "TEST_NEG"))))
                .andExpect(jsonPath("$.code").value(400));
    }

    // ========== 采购域：创建->审批(RBAC+留痕)->收货(自动入库)->删除（状态机约束） ==========

    @Test void purchaseOrderLifecycle() throws Exception {
        String t = token("admin", "admin123");
        String createBody = om.writeValueAsString(Map.of(
                "supplierId", 1,
                "remark", "BizTest 采购单",
                "items", List.of(Map.of("skuId", 1, "quantity", 5, "unitPrice", 28.5))
        ));
        MvcResult cr = mvc.perform(post("/api/purchase-orders-extra").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode data = om.readTree(cr.getResponse().getContentAsString()).path("data");
        long poId = data.path("id").asLong();
        String orderNo = data.path("orderNo").asText();
        assertThat(poId).isGreaterThan(0);
        assertThat(orderNo).startsWith("PO-");

        // 列表可见
        mvc.perform(get("/api/purchase-orders").header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.data.total").isNumber());

        // 明细
        mvc.perform(get("/api/purchase-orders-extra/" + poId).header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());

        // 非法状态名 -> 400
        mvc.perform(put("/api/purchase-orders-extra/" + poId + "/status").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("status", "ILLEGAL"))))
                .andExpect(jsonPath("$.code").value(400));

        Integer qtyBefore = jdbc.queryForObject(
                "SELECT quantity FROM inventory WHERE sku_id=1 AND warehouse_id=1", Integer.class);
        String flowBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_flow WHERE reason LIKE '采购入库 %'", String.class);

        // DRAFT -> APPROVED：留痕审批人与时间
        mvc.perform(put("/api/purchase-orders-extra/" + poId + "/status").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("status", "APPROVED"))))
                .andExpect(jsonPath("$.code").value(200));
        Map<String, Object> po = jdbc.queryForMap("SELECT status, approver, approved_at FROM purchase_order WHERE id=?", poId);
        assertThat(po.get("status")).isEqualTo("APPROVED");
        assertThat(po.get("approver")).isEqualTo("admin");
        assertThat(po.get("approved_at")).isNotNull();

        // 重复流转同一目标（DRAFT 前驱已不在）：CAS 拦下
        mvc.perform(put("/api/purchase-orders-extra/" + poId + "/status").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("status", "APPROVED"))))
                .andExpect(jsonPath("$.code").value(400));

        // APPROVED -> RECEIVED：自动入库 + 流水（采购→库存闭环）
        mvc.perform(put("/api/purchase-orders-extra/" + poId + "/status").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("status", "RECEIVED"))))
                .andExpect(jsonPath("$.code").value(200));
        Integer qtyAfter = jdbc.queryForObject(
                "SELECT quantity FROM inventory WHERE sku_id=1 AND warehouse_id=1", Integer.class);
        assertThat(qtyAfter).isEqualTo(qtyBefore + 5);
        String flowAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_flow WHERE reason LIKE '采购入库 %'", String.class);
        assertThat(Integer.parseInt(flowAfter)).isEqualTo(Integer.parseInt(flowBefore) + 1);

        // 终态不可再变更
        mvc.perform(put("/api/purchase-orders-extra/" + poId + "/status").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("status", "CANCELLED"))))
                .andExpect(jsonPath("$.code").value(400));

        // 已生效采购单不可物理删除
        mvc.perform(delete("/api/purchase-orders-extra/" + poId).header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.code").value(400));

        // DRAFT 可 CANCELLED 后删除
        String createBody2 = om.writeValueAsString(Map.of(
                "supplierId", 1, "remark", "BizTest 采购单2",
                "items", List.of(Map.of("skuId", 2, "quantity", 3, "unitPrice", 10.0))));
        MvcResult cr2 = mvc.perform(post("/api/purchase-orders-extra").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody2))
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        long poId2 = om.readTree(cr2.getResponse().getContentAsString()).path("data").path("id").asLong();
        mvc.perform(put("/api/purchase-orders-extra/" + poId2 + "/status").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("status", "CANCELLED"))))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(delete("/api/purchase-orders-extra/" + poId2).header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.code").value(200));
    }

    // ========== Agent 写工具真插库回归 ==========
    // 起因：实跑演练发现 created_by(BIGINT) 被塞入用户名字符串——该路径此前只有
    // Python 侧打桩测试，Java 侧从未真实落库，68 个用例全绿也没暴露。此用例封住缺口。

    @Test void agentWriteCreatesRealPurchaseOrderWithCreatorId() throws Exception {
        // quantity=42 与演示/演练常用参数(100)解耦，避免同 key 十分钟窗口内互相幂等拒绝；
        // 调用前预清理 + 结束后清理：测试重跑不依赖 Redis 键的过期时机
        String t = token("admin", "admin123");
        String idemKey = "idem:po:admin:1:SKU-T001-WH-M:42:9.9";
        stringRedis.delete(idemKey);
        MvcResult res = mvc.perform(post("/api/agent/purchase-orders")
                        .header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "supplierId", 1, "skuCode", "SKU-T001-WH-M",
                                "quantity", 42, "unitPrice", 9.9))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andReturn();
        String orderNo = om.readTree(res.getResponse().getContentAsString())
                .path("data").path("orderNo").asText();
        assertThat(orderNo).startsWith("PO-");
        // created_by 必须是 sys_user.id（数字），且明细行同事务落库
        Long adminId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin'", Long.class);
        Map<String, Object> po = jdbc.queryForMap(
                "SELECT created_by, total_amount FROM purchase_order WHERE order_no=?", orderNo);
        assertThat(((Number) po.get("created_by")).longValue()).isEqualTo(adminId);
        assertThat(((Number) jdbc.queryForObject(
                "SELECT quantity FROM purchase_order_item WHERE order_id=" +
                        "(SELECT id FROM purchase_order WHERE order_no=?)", Integer.class, orderNo))
                .intValue()).isEqualTo(42);
        // 幂等 key 在共享 Redis 里：成功路径按设计不释放（防10分钟内重复创建），测试必须自清
        stringRedis.delete(idemKey);
    }

    @Test void purchaseStatusChangeRequiresAdmin() throws Exception {
        String admin = token("admin", "admin123");
        String createBody = om.writeValueAsString(Map.of(
                "supplierId", 1, "remark", "RBAC 采购单",
                "items", List.of(Map.of("skuId", 1, "quantity", 1, "unitPrice", 1.0))));
        MvcResult cr = mvc.perform(post("/api/purchase-orders-extra").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        long poId = om.readTree(cr.getResponse().getContentAsString()).path("data").path("id").asLong();

        // 测试环境下 DemoUserInitializer 跑在 @Sql 建表之前会静默失败，ops 不在库里——测试自种：
        // 真实 BCrypt 哈希 + OPS 角色（生产由 initializer 负责，此处只复现"存在低权限账号"这一前提）
        jdbc.update("DELETE FROM sys_user WHERE username='ops'");
        jdbc.update("INSERT INTO sys_user(username, password_hash, nickname, role) VALUES ('ops', ?, '运营专员', 'OPS')",
                encoder.encode("ops123"));
        // OPS 角色无审批权：前端藏按钮不等于防线，API 层必须拦（DRAFT->APPROVED 是 ADMIN 专属决策）
        String ops = token("ops", "ops123");
        mvc.perform(put("/api/purchase-orders-extra/" + poId + "/status").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("status", "APPROVED"))))
                .andExpect(jsonPath("$.code").value(403));
        // 未登录同样拦截
        mvc.perform(put("/api/purchase-orders-extra/" + poId + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("status", "APPROVED"))))
                .andExpect(status().isForbidden());
        // 审批状态未被改动
        assertThat(jdbc.queryForObject("SELECT status FROM purchase_order WHERE id=?", String.class, poId))
                .isEqualTo("DRAFT");
    }

    @Test void purchaseOrderRequiresItems() throws Exception {
        String t = token();
        mvc.perform(post("/api/purchase-orders-extra").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("supplierId", 1, "items", List.of()))))
                .andExpect(jsonPath("$.code").value(400));
    }

    // ========== 合同域 ==========

    @Test void contractCrudAndRiskReportNotFoundReturnsFriendly() throws Exception {
        String t = token();
        String create = om.writeValueAsString(Map.of("title", "BizTest 合同 " + System.nanoTime(), "supplierId", 1, "amount", 12345));
        MvcResult cr = mvc.perform(post("/api/contracts-extra").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        long cid = om.readTree(cr.getResponse().getContentAsString()).path("data").path("id").asLong();
        assertThat(cid).isGreaterThan(0);

        // 详情含 riskReport 字段（无报告时为空）
        mvc.perform(get("/api/contracts-extra/" + cid).header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));

        // 无报告的 risk-report 接口返回友好提示
        mvc.perform(get("/api/contracts/" + cid + "/risk-report").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk()); // Result.fail 仍 200，code 非 200

        // 列表分页
        mvc.perform(get("/api/contracts").header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.data.total").isNumber());

        mvc.perform(delete("/api/contracts-extra/" + cid).header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test void contractRequiresTitle() throws Exception {
        String t = token();
        mvc.perform(post("/api/contracts-extra").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("title", ""))))
                .andExpect(jsonPath("$.code").value(400));
    }

    // ========== 商品/SKU/仓库 ==========

    @Test void productSkuWarehouseLifecycle() throws Exception {
        String t = token();
        // Product
        String pBody = om.writeValueAsString(Map.of("name", "BizTest 商品 " + System.nanoTime(), "category", "服装", "unit", "件"));
        MvcResult pr = mvc.perform(post("/api/products").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(pBody))
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        long pid = om.readTree(pr.getResponse().getContentAsString()).path("data").path("id").asLong();

        // SKU
        String skuCode = "SKU-BIZ-" + System.nanoTime();
        MvcResult sr = mvc.perform(post("/api/skus").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("productId", pid, "skuCode", skuCode, "spec", "测试规格", "costPrice", 10, "salePrice", 20))))
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        long sid = om.readTree(sr.getResponse().getContentAsString()).path("data").path("id").asLong();

        // Warehouse
        String wBody = om.writeValueAsString(Map.of("name", "BizTest 仓 " + System.nanoTime(), "location", "深圳"));
        MvcResult wr = mvc.perform(post("/api/warehouses").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(wBody))
                .andExpect(jsonPath("$.code").value(200)).andReturn();
        long wid = om.readTree(wr.getResponse().getContentAsString()).path("data").path("id").asLong();

        // 列表
        mvc.perform(get("/api/skus").param("keyword", "SKU-BIZ").header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/api/warehouses/options").header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.code").value(200));

        // 清理：SKU 有库存约束，先删库存关联再删 SKU；product 有 SKU 约束
        mvc.perform(delete("/api/skus/" + sid).header("Authorization", "Bearer " + t)).andExpect(jsonPath("$.code").value(200));
        mvc.perform(delete("/api/products/" + pid).header("Authorization", "Bearer " + t)).andExpect(jsonPath("$.code").value(200));
        mvc.perform(delete("/api/warehouses/" + wid).header("Authorization", "Bearer " + t)).andExpect(jsonPath("$.code").value(200));
    }

    // ========== BI ==========

    @Test void biStatsAndAnalyze() throws Exception {
        String t = token();
        mvc.perform(get("/api/bi/stats").header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.data.supplierCount").isNumber())
                .andExpect(jsonPath("$.data.lowStockCount").value(2));

        String body = om.writeValueAsString(Map.of("question", "查询所有供应商评分"));
        mvc.perform(post("/api/bi/analyze").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reply").isNotEmpty())
                .andExpect(jsonPath("$.data.data").isArray());
    }

    // ========== 知识库 RAG ==========

    @Test void knowledgeListAndRecall() throws Exception {
        String t = token();
        mvc.perform(get("/api/knowledge").header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.data.total").isNumber());
        mvc.perform(post("/api/knowledge/recall").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("query", "无限连带责任"))))
                .andExpect(jsonPath("$.data.context").isNotEmpty());
        mvc.perform(post("/api/knowledge/text").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("title", "BizTest 知识 " + System.nanoTime(), "content", "测试知识内容：禁止无限连带责任。"))))
                .andExpect(jsonPath("$.code").value(200));
    }

    // ========== Agent 全链路 ==========

    @Test void agentChatAndToolsList() throws Exception {
        String t = token();
        mvc.perform(get("/api/agent/tools").header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(7));
        mvc.perform(post("/api/agent/chat").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("message", "查询低库存SKU", "agentType", "general", "sessionId", "biz-t1"))))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.reply").isNotEmpty());
    }
}
