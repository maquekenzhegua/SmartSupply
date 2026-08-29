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

    private String token() throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", "admin", "password", "admin123"))))
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
        String t = token();
        // SKU 1 在仓库1当前 120，加 10 -> 130
        mvc.perform(post("/api/inventory/adjust").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("skuId", 1, "warehouseId", 1, "changeQty", 10, "reason", "TEST_BIZ"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));

        String after = mvc.perform(get("/api/inventory").header("Authorization", "Bearer " + t))
                .andReturn().getResponse().getContentAsString();
        // 校验至少有一条 130
        assertThat(after).contains("130");

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

    // ========== 采购域：创建->查询->状态流转->删除 ==========

    @Test void purchaseOrderLifecycle() throws Exception {
        String t = token();
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

        // 状态流转 DRAFT->APPROVED->RECEIVED，非法的回 400
        mvc.perform(put("/api/purchase-orders-extra/" + poId + "/status").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("status", "APPROVED"))))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(put("/api/purchase-orders-extra/" + poId + "/status").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("status", "ILLEGAL"))))
                .andExpect(jsonPath("$.code").value(400));

        // 删除
        mvc.perform(delete("/api/purchase-orders-extra/" + poId).header("Authorization", "Bearer " + t))
                .andExpect(jsonPath("$.code").value(200));
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
