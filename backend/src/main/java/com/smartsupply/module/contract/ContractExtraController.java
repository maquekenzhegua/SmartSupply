package com.smartsupply.module.contract;

import com.smartsupply.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/contracts-extra")
public class ContractExtraController {

    private final JdbcTemplate jdbc;
    public ContractExtraController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT c.*, s.name as supplier_name FROM contract c LEFT JOIN supplier s ON s.id=c.supplier_id WHERE c.id=?", id);
            try {
                Map<String, Object> report = jdbc.queryForMap("SELECT * FROM contract_risk_report WHERE contract_id=? ORDER BY id DESC LIMIT 1", id);
                row.put("riskReport", report);
            } catch (Exception ignored) {}
            return Result.ok(row);
        } catch (Exception e) { return Result.fail(404, "合同不存在"); }
    }

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Object title = body.get("title");
        if (title == null || String.valueOf(title).isBlank()) return Result.fail(400, "合同标题不能为空");
        jdbc.update("INSERT INTO contract(title, supplier_id, amount, sign_date, status) VALUES (?,?,?,?,?)",
                String.valueOf(title), body.get("supplierId"), body.get("amount"), body.get("signDate"), "DRAFT");
        Long id = com.smartsupply.common.DbHelper.lastInsertIdByUnique(jdbc, "contract", "title", String.valueOf(title));
        return Result.ok(Map.of("id", id == null ? 0 : id));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        int rows = jdbc.update("UPDATE contract SET title=?, supplier_id=?, amount=?, sign_date=?, status=? WHERE id=?",
                body.get("title"), body.get("supplierId"), body.get("amount"), body.get("signDate"), body.get("status"), id);
        if (rows == 0) return Result.fail(404, "合同不存在");
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        jdbc.update("DELETE FROM contract WHERE id=?", id);
        return Result.ok();
    }
}
