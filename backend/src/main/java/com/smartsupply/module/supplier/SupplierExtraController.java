package com.smartsupply.module.supplier;

import com.smartsupply.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/suppliers-extra")
public class SupplierExtraController {

    private final JdbcTemplate jdbc;
    public SupplierExtraController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/options")
    public Result<List<Map<String, Object>>> options() {
        return Result.ok(jdbc.queryForList("SELECT id, name FROM supplier ORDER BY rating DESC"));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        try {
            return Result.ok(jdbc.queryForMap("SELECT * FROM supplier WHERE id=?", id));
        } catch (Exception e) { return Result.fail(404, "供应商不存在"); }
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        int rows = jdbc.update("UPDATE supplier SET name=?, contact_name=?, contact_phone=?, email=?, address=?, rating=? WHERE id=?",
                body.get("name"), body.get("contactName"), body.get("contactPhone"), body.get("email"), body.get("address"), body.get("rating"), id);
        if (rows == 0) return Result.fail(404, "供应商不存在");
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        long po = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE supplier_id=?", Long.class, id);
        long ct = jdbc.queryForObject("SELECT COUNT(*) FROM contract WHERE supplier_id=?", Long.class, id);
        if (po + ct > 0) return Result.fail(400, "该供应商已被采购单或合同引用，无法删除");
        jdbc.update("DELETE FROM supplier WHERE id=?", id);
        return Result.ok();
    }
}
