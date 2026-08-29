package com.smartsupply.module.bi;

import com.smartsupply.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final JdbcTemplate jdbc;
    public StatsController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        Long supplierCount = jdbc.queryForObject("SELECT COUNT(*) FROM supplier", Long.class);
        Long productCount = jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class);
        Long skuCount = jdbc.queryForObject("SELECT COUNT(*) FROM sku", Long.class);
        Long warehouseCount = jdbc.queryForObject("SELECT COUNT(*) FROM warehouse", Long.class);
        Long lowStock = jdbc.queryForObject("SELECT COUNT(*) FROM inventory WHERE quantity < safety_stock", Long.class);
        Long poCount = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order", Long.class);
        Long contractCount = jdbc.queryForObject("SELECT COUNT(*) FROM contract", Long.class);
        return Result.ok(Map.of(
                "supplierCount", supplierCount == null ? 0 : supplierCount,
                "productCount", productCount == null ? 0 : productCount,
                "skuCount", skuCount == null ? 0 : skuCount,
                "warehouseCount", warehouseCount == null ? 0 : warehouseCount,
                "lowStockCount", lowStock == null ? 0 : lowStock,
                "poCount", poCount == null ? 0 : poCount,
                "contractCount", contractCount == null ? 0 : contractCount
        ));
    }

    @GetMapping("/category-stock")
    public Result<List<Map<String, Object>>> categoryStock() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.category, SUM(i.quantity) as total_qty, COUNT(*) as sku_cnt
                FROM inventory i JOIN sku s ON s.id=i.sku_id JOIN product p ON p.id=s.product_id
                GROUP BY p.category ORDER BY total_qty DESC
                """);
        return Result.ok(rows);
    }
}
