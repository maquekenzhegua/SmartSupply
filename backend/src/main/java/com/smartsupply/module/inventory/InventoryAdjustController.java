package com.smartsupply.module.inventory;

import com.smartsupply.common.CurrentUser;
import com.smartsupply.common.PageResult;
import com.smartsupply.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
public class InventoryAdjustController {

    private final JdbcTemplate jdbc;
    public InventoryAdjustController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/flows")
    public Result<PageResult<Map<String, Object>>> flows(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String skuCode) {
        if (skuCode != null && !skuCode.isBlank()) {
            String q = skuCode.trim();
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT f.*, s.sku_code, w.name as warehouse_name FROM inventory_flow f JOIN sku s ON s.id=f.sku_id JOIN warehouse w ON w.id=f.warehouse_id WHERE s.sku_code = ? ORDER BY f.id DESC LIMIT ? OFFSET ?",
                    q, size, (page - 1) * size);
            Long total = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_flow f JOIN sku s ON s.id=f.sku_id WHERE s.sku_code = ?", Long.class, q);
            return Result.ok(new PageResult<>(rows, total == null ? 0 : total, page, size));
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT f.*, s.sku_code, w.name as warehouse_name FROM inventory_flow f JOIN sku s ON s.id=f.sku_id JOIN warehouse w ON w.id=f.warehouse_id ORDER BY f.id DESC LIMIT ? OFFSET ?",
                size, (page - 1) * size);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_flow f", Long.class);
        return Result.ok(new PageResult<>(rows, total == null ? 0 : total, page, size));
    }

    // 库存增减直接动台账，统一 ADMIN（此前任意登录用户可无审计调整库存，与 Agent 写工具口径不一致）
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/adjust")
    @Transactional
    public Result<Void> adjust(@RequestBody Map<String, Object> body) {
        Long skuId = toLong(body.get("skuId"));
        Long warehouseId = toLong(body.get("warehouseId"));
        Integer changeQty = body.get("changeQty") == null ? null : Integer.parseInt(String.valueOf(body.get("changeQty")));
        String reason = String.valueOf(body.getOrDefault("reason", "MANUAL"));
        if (skuId == null || warehouseId == null || changeQty == null) return Result.fail(400, "skuId/warehouseId/changeQty 不能为空");
        if (changeQty == 0) return Result.fail(400, "调整数量不能为0");
        // 仓库与SKU校验
        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE id=?", Long.class, skuId);
        if (exists == null || exists == 0) return Result.fail(404, "SKU不存在");
        // 原子增减：单条 UPDATE 同时完成"加改 + 非负校验"（WHERE quantity + ? >= 0），
        // 替代此前"SELECT→Java 算 next→绝对值覆盖"的读-改-写——并发下两个调整互相覆盖、
        // 负库存校验存在 TOCTOU 竞态（超卖）。V7 另加 CHECK(quantity>=0) 数据库层兜底。
        int updated = jdbc.update(
                "UPDATE inventory SET quantity = quantity + ?, updated_at=now() WHERE sku_id=? AND warehouse_id=? AND quantity + ? >= 0",
                changeQty, skuId, warehouseId, changeQty);
        if (updated == 0) {
            // 0 行 = "行不存在"或"会扣成负数"，二者的用户语义不同，需区分返回
            List<Map<String, Object>> inv = jdbc.queryForList(
                    "SELECT quantity FROM inventory WHERE sku_id=? AND warehouse_id=?", skuId, warehouseId);
            if (inv.isEmpty()) {
                if (changeQty < 0) return Result.fail(400, "该仓库暂无此SKU，无法扣减");
                try {
                    jdbc.update("INSERT INTO inventory(sku_id, warehouse_id, quantity) VALUES (?,?,?)",
                            skuId, warehouseId, changeQty);
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    // 并发首建：UNIQUE(sku_id,warehouse_id) 冲突说明对方已插入，退避为一次带守卫的增量更新
                    int retry = jdbc.update(
                            "UPDATE inventory SET quantity = quantity + ?, updated_at=now() WHERE sku_id=? AND warehouse_id=? AND quantity + ? >= 0",
                            changeQty, skuId, warehouseId, changeQty);
                    if (retry == 0) return Result.fail(409, "库存被并发变更，请重试");
                }
            } else {
                long cur = ((Number) inv.get(0).get("quantity")).longValue();
                return Result.fail(400, "扣减后库存不能为负，当前库存" + cur);
            }
        }
        jdbc.update("INSERT INTO inventory_flow(sku_id, warehouse_id, change_qty, reason) VALUES (?,?,?,?)",
                skuId, warehouseId, changeQty, reason + " by " + CurrentUser.username());
        return Result.ok();
    }

    private Long toLong(Object v) { if (v == null) return null; try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return null; } }
}
