package com.smartsupply.module.product;

import com.smartsupply.common.PageResult;
import com.smartsupply.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skus")
public class SkuController {

    private final JdbcTemplate jdbc;
    public SkuController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String keyword) {
        List<Object> args = new java.util.ArrayList<>();
        String where = "WHERE 1=1 ";
        if (productId != null) { where += "AND s.product_id = ? "; args.add(productId); }
        if (keyword != null && !keyword.isBlank()) { where += "AND s.sku_code ILIKE ? "; args.add("%" + keyword.trim() + "%"); }
        List<Object> pageArgs = new java.util.ArrayList<>(args); pageArgs.add(size); pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.*, p.name as product_name, p.category FROM sku s JOIN product p ON p.id=s.product_id " + where + " ORDER BY s.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sku s " + where, Long.class, args.toArray());
        return Result.ok(new PageResult<>(rows, total == null ? 0 : total, page, size));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Object productId = body.get("productId");
        Object skuCode = body.get("skuCode");
        if (productId == null || skuCode == null || String.valueOf(skuCode).isBlank()) return Result.fail(400, "productId 与 skuCode 不能为空");
        Long id = com.smartsupply.common.DbHelper.insertAndReturnId(jdbc,
                "INSERT INTO sku(product_id, sku_code, spec, cost_price, sale_price) VALUES (?,?,?,?,?)",
                productId, String.valueOf(skuCode), body.get("spec"), body.get("costPrice"), body.get("salePrice"));
        return Result.ok(Map.of("id", id == null ? 0 : id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        int rows = jdbc.update("UPDATE sku SET sku_code=?, spec=?, cost_price=?, sale_price=? WHERE id=?",
                body.get("skuCode"), body.get("spec"), body.get("costPrice"), body.get("salePrice"), id);
        if (rows == 0) return Result.fail(404, "SKU不存在");
        return Result.ok();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        long inv = jdbc.queryForObject("SELECT COUNT(*) FROM inventory WHERE sku_id=?", Long.class, id);
        if (inv > 0) return Result.fail(400, "该SKU已有关联库存，无法删除");
        jdbc.update("DELETE FROM sku WHERE id=?", id);
        return Result.ok();
    }
}
