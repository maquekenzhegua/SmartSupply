package com.smartsupply.module.supplier;

import com.smartsupply.common.PageResult;
import com.smartsupply.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {
    private final JdbcTemplate jdbc;
    public SupplierController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="10") int size,
            @RequestParam(required=false) String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            String q = "%" + keyword.trim() + "%";
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM supplier WHERE name ILIKE ? ORDER BY rating DESC LIMIT ? OFFSET ?", q, size, (page-1)*size);
            Long total = jdbc.queryForObject("SELECT COUNT(*) FROM supplier WHERE name ILIKE ?", Long.class, q);
            return Result.ok(new PageResult<>(rows, total==null?0:total, page, size));
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM supplier ORDER BY rating DESC LIMIT ? OFFSET ?", size, (page-1)*size);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM supplier", Long.class);
        return Result.ok(new PageResult<>(rows, total==null?0:total, page, size));
    }

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        jdbc.update("INSERT INTO supplier(name, contact_name, contact_phone, email, address) VALUES (?,?,?,?,?)",
                body.get("name"), body.get("contactName"), body.get("contactPhone"), body.get("email"), body.get("address"));
        return Result.ok();
    }
}
