package com.smartsupply.module.purchase;

import com.smartsupply.common.PageResult;
import com.smartsupply.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseController {
    private final JdbcTemplate jdbc;
    public PurchaseController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="10") int size) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT po.*, s.name as supplier_name FROM purchase_order po LEFT JOIN supplier s ON s.id=po.supplier_id ORDER BY po.id DESC LIMIT ? OFFSET ?",
                size, (page-1)*size);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order", Long.class);
        return Result.ok(new PageResult<>(rows, total==null?0:total, page, size));
    }
}
