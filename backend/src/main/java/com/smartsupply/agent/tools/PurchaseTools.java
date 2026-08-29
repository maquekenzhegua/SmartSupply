package com.smartsupply.agent.tools;

import com.smartsupply.agent.IdempotencyService;
import com.smartsupply.agent.ObservationService;
import com.smartsupply.common.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PurchaseTools {

    private static final Logger log = LoggerFactory.getLogger(PurchaseTools.class);
    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final ToolSecurity security;
    private final ObservationService observation;

    public PurchaseTools(JdbcTemplate jdbc, IdempotencyService idempotency, ToolSecurity security, ObservationService observation) {
        this.jdbc = jdbc; this.idempotency = idempotency; this.security = security; this.observation = observation;
    }

    @Tool(description = "创建采购单，需指定供应商ID、SKU编码、数量和单价。幂等：相同 supplierId+skuCode+quantity+unitPrice 在10分钟内仅创建一次。仅在用户明确授权且低于安全库存时调用，创建后为 DRAFT 需人工审批。返回采购单号")
    public Map<String, Object> createPurchaseOrder(
            @ToolParam(description = "供应商ID") Long supplierId,
            @ToolParam(description = "SKU编码") String skuCode,
            @ToolParam(description = "采购数量") int quantity,
            @ToolParam(description = "单价") double unitPrice) {
        long start = System.currentTimeMillis();
        boolean ok = false;
        try {
            security.requireSupplierWritePerm("createPurchaseOrder");
            if (supplierId == null || skuCode == null || skuCode.isBlank()) throw new IllegalArgumentException("supplierId 与 skuCode 不能为空");
            if (quantity <= 0 || quantity > 100000) throw new IllegalArgumentException("数量需在 1..100000");
            if (unitPrice < 0 || unitPrice > 1000000) throw new IllegalArgumentException("单价不合法");
            skuCode = skuCode.trim().toUpperCase();
            String idemKey = "idem:po:" + CurrentUser.username() + ":" + supplierId + ":" + skuCode + ":" + quantity + ":" + unitPrice;
            if (!idempotency.tryAcquire(idemKey, Duration.ofMinutes(10))) {
                ok = true;
                return Map.of("success", false, "duplicate", true, "msg", "重复提交，已在10分钟内创建过相同采购单");
            }
            Long supplierExists = jdbc.queryForObject("SELECT COUNT(*) FROM supplier WHERE id=?", Long.class, supplierId);
            if (supplierExists == null || supplierExists == 0) throw new IllegalArgumentException("供应商不存在: " + supplierId);
            Long skuId = null;
            try { skuId = jdbc.queryForObject("SELECT id FROM sku WHERE sku_code=?", Long.class, skuCode); } catch (Exception ignored) {}
            if (skuId == null) throw new IllegalArgumentException("SKU不存在: " + skuCode);
            List<Map<String, Object>> lows = jdbc.queryForList(
                    "SELECT quantity, safety_stock FROM inventory WHERE sku_id=?", skuId);
            boolean below = lows.stream().anyMatch(r -> ((Number) r.get("quantity")).intValue() < ((Number) r.get("safety_stock")).intValue());
            String orderNo = "PO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            double amount = quantity * unitPrice;
            String actor = CurrentUser.username();
            log.info("createPurchaseOrder by={} supplier={} sku={} qty={} price={} belowSafety={}", actor, supplierId, skuCode, quantity, unitPrice, below);
            jdbc.update("INSERT INTO purchase_order(order_no, supplier_id, status, total_amount, remark, created_by) VALUES (?,?,?,?,?,?)",
                    orderNo, supplierId, "DRAFT", amount, "由Agent创建 操作人:" + actor + (below ? " [低于安全库存]" : " [库存校验通过]"), null);
            Long orderId = com.smartsupply.common.DbHelper.lastInsertIdByUnique(jdbc, "purchase_order", "order_no", orderNo);
            jdbc.update("INSERT INTO purchase_order_item(order_id, sku_id, quantity, unit_price, amount) VALUES (?,?,?,?,?)",
                    orderId, skuId, quantity, unitPrice, amount);
            ok = true;
            return Map.of("success", true, "orderNo", orderNo, "orderId", orderId, "totalAmount", amount, "belowSafety", below);
        } finally {
            observation.recordTool("createPurchaseOrder", ok, System.currentTimeMillis() - start);
        }
    }

    @Tool(description = "查询供应商列表，按评分降序，用于选供应商")
    public java.util.List<Map<String, Object>> listSuppliers() {
        long start = System.currentTimeMillis();
        security.requireRead("listSuppliers");
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, name, rating, status FROM supplier ORDER BY rating DESC");
            observation.recordTool("listSuppliers", true, System.currentTimeMillis() - start);
            return rows;
        } catch (Exception e) {
            observation.recordTool("listSuppliers", false, System.currentTimeMillis() - start);
            throw e;
        }
    }
}
