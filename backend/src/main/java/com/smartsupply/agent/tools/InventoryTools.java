package com.smartsupply.agent.tools;

import com.smartsupply.agent.ObservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class InventoryTools {

    private static final Logger log = LoggerFactory.getLogger(InventoryTools.class);
    private static final Pattern SKU_PATTERN = Pattern.compile("^[A-Z0-9-]{3,32}$");
    private final JdbcTemplate jdbc;
    private final ObservationService observation;

    public InventoryTools(JdbcTemplate jdbc, ObservationService observation) { this.jdbc = jdbc; this.observation = observation; }

    @Tool(description = "查询指定 SKU 的库存与安全库存，返回 quantity / safety_stock / 是否低于安全库存")
    public Map<String, Object> getInventory(
            @ToolParam(description = "SKU编码，如 SKU-T001-WH-M") String skuCode) {
        long start = System.currentTimeMillis();
        boolean ok = false;
        try {
            if (skuCode == null || skuCode.isBlank()) throw new IllegalArgumentException("skuCode 不能为空");
            String code = skuCode.trim().toUpperCase();
            if (!SKU_PATTERN.matcher(code).matches()) throw new IllegalArgumentException("SKU 格式不合法");
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT s.sku_code, w.name as warehouse, i.quantity, i.safety_stock
                    FROM inventory i JOIN sku s ON s.id=i.sku_id JOIN warehouse w ON w.id=i.warehouse_id
                    WHERE s.sku_code=?
                    """, code);
            if (rows.isEmpty()) { ok = true; return Map.of("found", false, "msg", "未找到该 SKU"); }
            Map<String, Object> r = rows.get(0);
            int qty = ((Number) r.get("quantity")).intValue();
            int safe = ((Number) r.get("safety_stock")).intValue();
            ok = true;
            return Map.of("found", true, "data", r, "belowSafety", qty < safe,
                    "advice", qty < safe ? "建议补货" : "库存充足");
        } finally {
            observation.recordTool("getInventory", ok, System.currentTimeMillis() - start);
        }
    }

    @Tool(description = "查询所有低于安全库存的 SKU 列表，按缺口降序")
    public List<Map<String, Object>> listLowStock() {
        long start = System.currentTimeMillis();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT s.sku_code, s.spec, w.name as warehouse, i.quantity, i.safety_stock
                    FROM inventory i JOIN sku s ON s.id=i.sku_id JOIN warehouse w ON w.id=i.warehouse_id
                    WHERE i.quantity < i.safety_stock
                    ORDER BY (i.safety_stock - i.quantity) DESC
                    """);
            observation.recordTool("listLowStock", true, System.currentTimeMillis() - start);
            return rows;
        } catch (Exception e) {
            observation.recordTool("listLowStock", false, System.currentTimeMillis() - start);
            throw e;
        }
    }
}
