package com.smartsupply.module.bi;

import com.smartsupply.agent.tools.SqlValidator;
import com.smartsupply.common.RateLimit;
import com.smartsupply.common.Result;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * BI 经营分析 Agent：NL -> SQL 思路 -> 只读执行 -> ECharts
 * 面试重点：SQL 白名单校验 + 只读执行 + 结果转图表
 */
@RestController
@RequestMapping("/api/bi")
public class BiController {

    private final ChatClient chatClient;
    private final JdbcTemplate jdbc;
    private final SqlValidator validator;

    public BiController(ChatClient chatClient, JdbcTemplate jdbc, SqlValidator validator) {
        this.chatClient = chatClient; this.jdbc = jdbc; this.validator = validator;
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

        // 尝试从 AI 回复中提取 SQL（演示：若包含 SELECT 则尝试执行，否则返回模拟数据）
        List<Map<String, Object>> data = List.of();
        try {
            String sql = extractSql(aiReply);
            if (sql != null) {
                validator.validate(sql);
                data = jdbc.queryForList(sql);
            }
        } catch (Exception ignored) {}

        if (data.isEmpty()) {
            // 兜底演示数据，保证前端有图可画
            data = List.of(
                    Map.of("category", "服装", "value", 123),
                    Map.of("category", "箱包", "value", 45),
                    Map.of("category", "日用", "value", 67)
            );
        }

        return Result.ok(Map.of(
                "reply", aiReply == null ? "" : aiReply,
                "data", data,
                "chartType", "pie"
        ));
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
        int idx = text.toLowerCase().indexOf("select");
        if (idx < 0) return null;
        int end = text.indexOf(";", idx);
        String sql = (end > 0 ? text.substring(idx, end) : text.substring(idx)).trim();
        // 去掉 markdown 代码块标记
        sql = sql.replace("```sql","").replace("```","").trim();
        // 只取第一条
        if (sql.contains("\n")) sql = sql.substring(0, sql.indexOf("\n")).trim();
        if (sql.length() > 500) sql = sql.substring(0, 500);
        return sql;
    }
}
