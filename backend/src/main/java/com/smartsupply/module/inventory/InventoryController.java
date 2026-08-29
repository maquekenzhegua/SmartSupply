package com.smartsupply.module.inventory;

import com.smartsupply.common.PageResult;
import com.smartsupply.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final JdbcTemplate jdbc;
    public InventoryController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="10") int size) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.id, s.sku_code, s.spec, p.name as product_name, p.category,
                       w.name as warehouse, i.quantity, i.safety_stock,
                       CASE WHEN i.quantity < i.safety_stock THEN true ELSE false END as below_safety
                FROM inventory i JOIN sku s ON s.id=i.sku_id JOIN product p ON p.id=s.product_id
                JOIN warehouse w ON w.id=i.warehouse_id
                ORDER BY below_safety DESC, i.quantity ASC LIMIT ? OFFSET ?
                """, size, (page-1)*size);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM inventory", Long.class);
        return Result.ok(new PageResult<>(rows, total==null?0:total, page, size));
    }

    @GetMapping("/low-stock")
    public Result<List<Map<String, Object>>> lowStock() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.sku_code, s.spec, w.name as warehouse, i.quantity, i.safety_stock
                FROM inventory i JOIN sku s ON s.id=i.sku_id JOIN warehouse w ON w.id=i.warehouse_id
                WHERE i.quantity < i.safety_stock ORDER BY (i.safety_stock - i.quantity) DESC
                """);
        return Result.ok(rows);
    }
}
