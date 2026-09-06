package com.smartsupply.module.purchase;

import com.smartsupply.common.CurrentUser;
import com.smartsupply.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/purchase-orders-extra")
public class PurchaseExtraController {

    private final JdbcTemplate jdbc;
    public PurchaseExtraController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 采购单状态机：DRAFT -> APPROVED -> RECEIVED，任一非终态可 CANCELLED；RECEIVED/CANCELLED 为终态。
     * 状态流转与到货入库在同一事务；流转用 CAS（WHERE status=当前值）防并发/重复放行。
     */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "DRAFT", Set.of("APPROVED", "CANCELLED"),
            "APPROVED", Set.of("RECEIVED", "CANCELLED"));

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        try {
            Map<String, Object> po = jdbc.queryForMap(
                    "SELECT po.*, s.name as supplier_name FROM purchase_order po LEFT JOIN supplier s ON s.id=po.supplier_id WHERE po.id=?", id);
            List<Map<String, Object>> items = jdbc.queryForList(
                    "SELECT i.*, s.sku_code, s.spec, p.name as product_name FROM purchase_order_item i JOIN sku s ON s.id=i.sku_id JOIN product p ON p.id=s.product_id WHERE i.order_id=?", id);
            po.put("items", items);
            return Result.ok(po);
        } catch (Exception e) { return Result.fail(404, "采购单不存在"); }
    }

    @PostMapping
    @Transactional
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Object supplierId = body.get("supplierId");
        Object remark = body.get("remark");
        Object itemsObj = body.get("items");
        if (!(itemsObj instanceof List<?> items) || items.isEmpty()) return Result.fail(400, "采购明细不能为空");
        String orderNo = "PO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        double total = 0;
        for (Object it : items) {
            if (it instanceof Map<?,?> m) {
                double price = m.get("unitPrice") == null ? 0 : Double.parseDouble(String.valueOf(m.get("unitPrice")));
                int qty = m.get("quantity") == null ? 0 : Integer.parseInt(String.valueOf(m.get("quantity")));
                total += price * qty;
            }
        }
        jdbc.update("INSERT INTO purchase_order(order_no, supplier_id, status, total_amount, remark) VALUES (?,?,?,?,?)",
                orderNo, supplierId, "DRAFT", total, remark);
        Long orderId = com.smartsupply.common.DbHelper.lastInsertIdByUnique(jdbc, "purchase_order", "order_no", orderNo);
        for (Object it : items) {
            if (it instanceof Map<?,?> m) {
                Long skuId = Long.parseLong(String.valueOf(m.get("skuId")));
                int qty = Integer.parseInt(String.valueOf(m.get("quantity")));
                double price = Double.parseDouble(String.valueOf(m.get("unitPrice")));
                jdbc.update("INSERT INTO purchase_order_item(order_id, sku_id, quantity, unit_price, amount) VALUES (?,?,?,?,?)",
                        orderId, skuId, qty, price, qty * price);
            }
        }
        return Result.ok(Map.of("id", orderId == null ? 0 : orderId, "orderNo", orderNo));
    }

    @PutMapping("/{id}/status")
    @Transactional
    public Result<Map<String, Object>> updateStatus(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String status = String.valueOf(body.get("status"));
        if (!List.of("DRAFT", "APPROVED", "RECEIVED", "CANCELLED").contains(status)) return Result.fail(400, "非法状态");
        // 审批/收货/取消是运营决策动作：API 层强制 ADMIN（与 Agent 写工具 requireSupplierWritePerm 同一角色口径，
        // 前端隐藏按钮不等于防线——直接 curl 也必须被 RBAC 拦下）
        if (!CurrentUser.hasRole("ADMIN")) return Result.fail(403, "采购单状态流转需要 ADMIN 角色");
        String current;
        try {
            current = jdbc.queryForObject("SELECT status FROM purchase_order WHERE id=?", String.class, id);
        } catch (Exception e) {
            return Result.fail(404, "采购单不存在");
        }
        Set<String> allowed = ALLOWED_TRANSITIONS.get(current);
        if (allowed == null || !allowed.contains(status)) {
            return Result.fail(400, "非法状态流转: " + current + " -> " + status
                    + (allowed == null || allowed.isEmpty() ? "（终态不可再变更）" : "，允许: " + allowed));
        }
        int rows;
        if ("APPROVED".equals(status)) {
            // CAS：WHERE 带当前状态，并发/重复请求最多一个成功；审批人与审批时间在此留痕
            rows = jdbc.update("UPDATE purchase_order SET status=?, approver=?, approved_at=now() WHERE id=? AND status=?",
                    status, CurrentUser.username(), id, current);
        } else {
            rows = jdbc.update("UPDATE purchase_order SET status=? WHERE id=? AND status=?", status, id, current);
        }
        if (rows == 0) return Result.fail(409, "采购单状态已被并发变更，请刷新后重试");
        if ("RECEIVED".equals(status)) inbound(id);
        return Result.ok(Map.of("id", id, "status", status));
    }

    /** 到货入库：按采购明细对每个 SKU 入库（演示口径收货入该 SKU 现有主仓；无库存行则建在 1 号仓），
     *  并写 inventory_flow 流水（与手动调整同一套账）。仅由状态机 RECEIVED 分支调用一次，天然幂等。 */
    private void inbound(long orderId) {
        String orderNo = jdbc.queryForObject("SELECT order_no FROM purchase_order WHERE id=?", String.class, orderId);
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT sku_id, quantity FROM purchase_order_item WHERE order_id=?", orderId);
        for (Map<String, Object> it : items) {
            long skuId = ((Number) it.get("sku_id")).longValue();
            int qty = ((Number) it.get("quantity")).intValue();
            List<Long> whs = jdbc.queryForList(
                    "SELECT warehouse_id FROM inventory WHERE sku_id=? ORDER BY warehouse_id", Long.class, skuId);
            long whId = whs.isEmpty() ? 1L : whs.get(0);
            if (whs.isEmpty()) {
                jdbc.update("INSERT INTO inventory(sku_id, warehouse_id, quantity) VALUES (?,?,?)", skuId, whId, qty);
            } else {
                jdbc.update("UPDATE inventory SET quantity=quantity+?, updated_at=now() WHERE sku_id=? AND warehouse_id=?",
                        qty, skuId, whId);
            }
            jdbc.update("INSERT INTO inventory_flow(sku_id, warehouse_id, change_qty, reason) VALUES (?,?,?,?)",
                    skuId, whId, qty, "采购入库 " + orderNo);
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        // 业务约束：已生效（APPROVED/RECEIVED）的采购单不允许物理删除，只能走 CANCELLED
        int rows = jdbc.update("DELETE FROM purchase_order WHERE id=? AND status IN ('DRAFT','CANCELLED')", id);
        if (rows == 0) return Result.fail(400, "仅 DRAFT/CANCELLED 状态的采购单可删除");
        return Result.ok();
    }
}
