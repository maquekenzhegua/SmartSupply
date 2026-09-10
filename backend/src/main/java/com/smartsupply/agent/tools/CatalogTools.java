package com.smartsupply.agent.tools;

import com.smartsupply.agent.ObservationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CatalogTools {

    private final JdbcTemplate jdbc;
    private final ObservationService observation;
    private final ToolSecurity security;
    public CatalogTools(JdbcTemplate jdbc, ObservationService observation, ToolSecurity security) { this.jdbc = jdbc; this.observation = observation; this.security = security; }

    @Tool(description = "搜索商品与SKU，返回 product_name/sku_code/spec/sale_price，关键词走参数化 ILIKE")
    public List<Map<String, Object>> searchCatalog(
            @ToolParam(description = "关键词，如 T恤/箱包/白色") String keyword,
            org.springframework.ai.chat.model.ToolContext toolContext) {
        long start = System.currentTimeMillis();
        security.requireRead("searchCatalog", toolContext);
        try {
            String q = "%" + (keyword == null ? "" : keyword.trim()) + "%";
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT p.name as product_name, s.sku_code, s.spec, s.sale_price, p.category
                    FROM sku s JOIN product p ON p.id=s.product_id
                    WHERE p.name ILIKE ? OR s.sku_code ILIKE ? OR s.spec ILIKE ?
                    ORDER BY p.id LIMIT 10
                    """, q, q, q);
            observation.recordTool("searchCatalog", true, System.currentTimeMillis() - start);
            return rows;
        } catch (Exception e) {
            observation.recordTool("searchCatalog", false, System.currentTimeMillis() - start);
            throw e;
        }
    }
}
