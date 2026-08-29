package com.smartsupply.module.product;

import com.smartsupply.common.PageResult;
import com.smartsupply.common.Result;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final JdbcTemplate jdbc;
    public ProductController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record CreateReq(@NotBlank String name, String category, String unit, String barCode) {}

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        List<Object> args = new java.util.ArrayList<>();
        String where = "WHERE 1=1 ";
        if (keyword != null && !keyword.isBlank()) { where += "AND p.name ILIKE ? "; args.add("%" + keyword.trim() + "%"); }
        if (category != null && !category.isBlank()) { where += "AND p.category = ? "; args.add(category.trim()); }
        List<Object> pageArgs = new java.util.ArrayList<>(args); pageArgs.add(size); pageArgs.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.*, (SELECT COUNT(*) FROM sku s WHERE s.product_id=p.id) as sku_count FROM product p " + where + " ORDER BY p.id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM product p " + where, Long.class, args.toArray());
        return Result.ok(new PageResult<>(rows, total == null ? 0 : total, page, size));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM product WHERE id=?", id);
        List<Map<String, Object>> skus = jdbc.queryForList("SELECT * FROM sku WHERE product_id=? ORDER BY id", id);
        row.put("skus", skus);
        return Result.ok(row);
    }

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody CreateReq req) {
        if (req.name() == null || req.name().isBlank()) return Result.fail(400, "商品名称不能为空");
        jdbc.update("INSERT INTO product(name, category, unit, bar_code) VALUES (?,?,?,?)",
                req.name(), req.category(), req.unit(), req.barCode());
        Long id = com.smartsupply.common.DbHelper.lastInsertIdByUnique(jdbc, "product", "name", req.name());
        return Result.ok(Map.of("id", id == null ? 0 : id));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable long id, @RequestBody CreateReq req) {
        int rows = jdbc.update("UPDATE product SET name=?, category=?, unit=?, bar_code=? WHERE id=?",
                req.name(), req.category(), req.unit(), req.barCode(), id);
        if (rows == 0) return Result.fail(404, "商品不存在");
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        long skuCnt = jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE product_id=?", Long.class, id);
        if (skuCnt > 0) return Result.fail(400, "该商品下还有SKU，无法删除");
        jdbc.update("DELETE FROM product WHERE id=?", id);
        return Result.ok();
    }
}
