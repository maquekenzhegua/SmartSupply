package com.smartsupply.module.warehouse;

import com.smartsupply.common.PageResult;
import com.smartsupply.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/warehouses")
public class WarehouseController {

    private final JdbcTemplate jdbc;
    public WarehouseController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM warehouse ORDER BY id LIMIT ? OFFSET ?", size, (page - 1) * size);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM warehouse", Long.class);
        return Result.ok(new PageResult<>(rows, total == null ? 0 : total, page, size));
    }

    @GetMapping("/options")
    public Result<List<Map<String, Object>>> options() {
        return Result.ok(jdbc.queryForList("SELECT id, name FROM warehouse ORDER BY id"));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Object name = body.get("name");
        if (name == null || String.valueOf(name).isBlank()) return Result.fail(400, "仓库名称不能为空");
        Long id = com.smartsupply.common.DbHelper.insertAndReturnId(jdbc,
                "INSERT INTO warehouse(name, location) VALUES (?,?)", String.valueOf(name), body.get("location"));
        return Result.ok(Map.of("id", id == null ? 0 : id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        int rows = jdbc.update("UPDATE warehouse SET name=?, location=? WHERE id=?", body.get("name"), body.get("location"), id);
        if (rows == 0) return Result.fail(404, "仓库不存在");
        return Result.ok();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM inventory WHERE warehouse_id=?", Long.class, id);
        if (cnt > 0) return Result.fail(400, "该仓库下还有库存，无法删除");
        jdbc.update("DELETE FROM warehouse WHERE id=?", id);
        return Result.ok();
    }
}
