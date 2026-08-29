package com.smartsupply.module.purchase;

import com.smartsupply.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/purchase-orders-extra")
public class PurchaseExtraController {

    private final JdbcTemplate jdbc;
    public PurchaseExtraController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
    public Result<Void> updateStatus(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String status = String.valueOf(body.get("status"));
        if (!List.of("DRAFT", "APPROVED", "RECEIVED", "CANCELLED").contains(status)) return Result.fail(400, "非法状态");
        int rows = jdbc.update("UPDATE purchase_order SET status=? WHERE id=?", status, id);
        if (rows == 0) return Result.fail(404, "采购单不存在");
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        jdbc.update("DELETE FROM purchase_order WHERE id=?", id);
        return Result.ok();
    }
}
