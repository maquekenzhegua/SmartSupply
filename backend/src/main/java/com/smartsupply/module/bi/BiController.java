package com.smartsupply.module.bi;

import com.smartsupply.agent.tools.SqlValidator;
import com.smartsupply.common.RateLimit;
import com.smartsupply.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BI 经营分析 Agent：NL -> SQL 提取 -> 静态校验 -> 只读执行 -> ECharts
 * 执行约束由 ReadOnlyQueryGateway 兜底（只读连接 + 超时 + 行数上限）。
 * 结果不真实即如实报错（sqlError 字段），绝不返回编造的演示数据冒充查询结果。
 */
@RestController
@RequestMapping("/api/bi")
public class BiController {

    private static final Logger log = LoggerFactory.getLogger(BiController.class);

    private final ChatClient chatClient;
    private final JdbcTemplate jdbc;
    private final SqlValidator validator;
    private final ReadOnlyQueryGateway gateway;

    public BiController(ChatClient chatClient, JdbcTemplate jdbc, SqlValidator validator, ReadOnlyQueryGateway gateway) {
        this.chatClient = chatClient; this.jdbc = jdbc; this.validator = validator; this.gateway = gateway;
    }

    @PostMapping("/analyze")
    @RateLimit(permitsPerMinute = 20, key = "bi-analyze")
    public Result<Map<String, Object>> analyze(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "");
        String prompt = """
                你是供应链 BI 分析师。用户问题：%s
                请先用一句 SQL 思路说明你会怎么查（只查 supplier/product/sku/inventory/purchase/contract 表，SELECT 只读），
                然后给出结论与 1 个 ECharts 图表建议（pie/bar/line）。
                若无法写出安全 SQL，请直接文字分析。
                """.formatted(question);

        String aiReply = chatClient.prompt()
                .system("你是严谨的 BI 分析 Agent，只做只读分析，不生成 DML。")
                .user(prompt).call().content();

        List<Map<String, Object>> data = List.of();
        String sqlError = null;
        try {
            String sql = extractSql(aiReply);
            if (sql == null) {
                sqlError = "模型未给出可执行的 SELECT 语句，仅返回文字分析";
            } else {
                validator.validate(sql);
                data = gateway.query(sql);
            }
        } catch (Exception e) {
            // 校验/执行失败如实上报：空数据 + sqlError，不再用编造数据冒充查询结果
            sqlError = e.getMessage() == null ? e.toString() : e.getMessage();
            log.info("BI analyze SQL 未执行成功: {}", sqlError);
        }

        Map<String, Object> out = new HashMap<>();
        out.put("reply", aiReply == null ? "" : aiReply);
        out.put("data", data);
        out.put("chartType", "pie");
        if (sqlError != null) out.put("sqlError", sqlError);
        return Result.ok(out);
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        Long supplierCount = jdbc.queryForObject("SELECT COUNT(*) FROM supplier", Long.class);
        Long skuCount = jdbc.queryForObject("SELECT COUNT(*) FROM sku", Long.class);
        Long lowStock = jdbc.queryForObject("SELECT COUNT(*) FROM inventory WHERE quantity < safety_stock", Long.class);
        Long poCount = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order", Long.class);
        List<Map<String, Object>> lowList = jdbc.queryForList("""
                SELECT s.sku_code, i.quantity, i.safety_stock FROM inventory i JOIN sku s ON s.id=i.sku_id
                WHERE i.quantity < i.safety_stock LIMIT 5
                """);
        return Result.ok(Map.of(
                "supplierCount", supplierCount == null ? 0 : supplierCount,
                "skuCount", skuCount == null ? 0 : skuCount,
                "lowStockCount", lowStock == null ? 0 : lowStock,
                "poCount", poCount == null ? 0 : poCount,
                "lowStockList", lowList
        ));
    }

    private String extractSql(String text) {
        if (text == null) return null;
        // 优先取 ```sql 围栏块（模型最常见输出形态，允许多行）
        java.util.regex.Matcher fence = java.util.regex.Pattern
                .compile("(?is)```sql\\s*(.+?)```").matcher(text);
        if (fence.find()) return fence.group(1).trim();
        int idx = text.toLowerCase().indexOf("select");
        if (idx < 0) return null;
        int end = text.indexOf(";", idx);
        String sql = (end > 0 ? text.substring(idx, end) : text.substring(idx)).trim();
        if (sql.length() > 500) sql = sql.substring(0, 500);
        return sql;
    }
}
